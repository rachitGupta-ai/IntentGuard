package com.intentguard.intent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;

/**
 * Unit tests for {@link DefaultIntentSessionManager}. The repositories are mocked so no live
 * Datastore is required. Covers: human open/close/modify persist correctly (Req 4.1, 4.3);
 * activeSessionFor returns the open session (Req 4.2); and agent open/close/modify is rejected, the
 * session is left unchanged, and a rejected-attempt audit record is written (Req 13.3).
 */
class DefaultIntentSessionManagerTest {

    private static final long NOW = 1_700_000_000_000L;

    private IntentSessionRepository sessionRepository;
    private AuditHistoryRepository auditHistoryRepository;
    private DefaultIntentSessionManager manager;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(IntentSessionRepository.class);
        auditHistoryRepository = mock(AuditHistoryRepository.class);
        manager = new DefaultIntentSessionManager(sessionRepository, auditHistoryRepository);
        manager.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // --- Human happy path ---------------------------------------------------------------------

    @Test
    void humanOpenPersistsSessionWithIntentUserAndStartTimestamp() {
        IntentSession session = manager.open("alice", "deploy the web service", Actor.human("alice"));

        ArgumentCaptor<IntentSessionDocument> captor =
                ArgumentCaptor.forClass(IntentSessionDocument.class);
        verify(sessionRepository).save(captor.capture());
        IntentSessionDocument saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo("alice");
        assertThat(saved.getDeclaredIntent()).isEqualTo("deploy the web service");
        assertThat(saved.getIntentSource()).isEqualTo(IntentSource.DECLARED.name());
        assertThat(saved.getStartedAt()).isEqualTo(NOW);
        assertThat(saved.getEndedAt()).isNull();
        assertThat(saved.isOpen()).isTrue();
        assertThat(saved.getSessionId()).isNotBlank();

        // Returned domain object mirrors what was persisted.
        assertThat(session.sessionId()).isEqualTo(saved.getSessionId());
        assertThat(session.intentSource()).isEqualTo(IntentSource.DECLARED);
        assertThat(session.open()).isTrue();
        assertThat(session.startedAt()).isEqualTo(NOW);

        // No rejection recorded for a human request.
        verifyNoInteractions(auditHistoryRepository);
    }

    @Test
    void humanCloseRecordsEndTimestampAndMarksClosed() {
        IntentSessionDocument existing = openDocument("s-1", "bob", "investigate logs");
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(existing));

        manager.close("s-1", Actor.human("bob"));

        ArgumentCaptor<IntentSessionDocument> captor =
                ArgumentCaptor.forClass(IntentSessionDocument.class);
        verify(sessionRepository).save(captor.capture());
        IntentSessionDocument saved = captor.getValue();

        assertThat(saved.isOpen()).isFalse();
        assertThat(saved.getEndedAt()).isEqualTo(NOW);
        verifyNoInteractions(auditHistoryRepository);
    }

    @Test
    void humanModifyUpdatesDeclaredIntentAndPersists() {
        IntentSessionDocument existing = openDocument("s-2", "carol", "old goal");
        when(sessionRepository.findBySessionId("s-2")).thenReturn(Optional.of(existing));

        manager.modify("s-2", new IntentChange("new goal"), Actor.human("carol"));

        ArgumentCaptor<IntentSessionDocument> captor =
                ArgumentCaptor.forClass(IntentSessionDocument.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getDeclaredIntent()).isEqualTo("new goal");
        assertThat(captor.getValue().isOpen()).isTrue();
        verifyNoInteractions(auditHistoryRepository);
    }

    @Test
    void closeUnknownSessionThrows() {
        when(sessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.close("missing", Actor.human("bob")))
                .isInstanceOf(NoSuchElementException.class);
        verify(sessionRepository, never()).save(any());
    }

    // --- activeSessionFor ---------------------------------------------------------------------

    @Test
    void activeSessionForReturnsOpenSession() {
        IntentSessionDocument open = openDocument("s-3", "dave", "run migration");
        when(sessionRepository.findOpenByUserId("dave")).thenReturn(Optional.of(open));

        Optional<IntentSession> result = manager.activeSessionFor("dave");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().sessionId()).isEqualTo("s-3");
        assertThat(result.orElseThrow().declaredIntent()).isEqualTo("run migration");
        assertThat(result.orElseThrow().open()).isTrue();
    }

    @Test
    void activeSessionForReturnsEmptyWhenNoOpenSession() {
        when(sessionRepository.findOpenByUserId("erin")).thenReturn(Optional.empty());

        assertThat(manager.activeSessionFor("erin")).isEmpty();
    }

    // --- Agent rejection (Req 13.3) -----------------------------------------------------------

    @Test
    void agentOpenIsRejectedRecordedAndDoesNotOpenSession() {
        Actor agent = Actor.agent("agent-1", "alice");

        assertThatThrownBy(() -> manager.open("alice", "exfiltrate data", agent))
                .isInstanceOf(AgentIntentMutationException.class);

        // No session opened.
        verify(sessionRepository, never()).save(any());
        // Rejected attempt recorded.
        assertRejectionRecorded(agent, null);
    }

    @Test
    void agentCloseIsRejectedRecordedAndPreservesSessionUnchanged() {
        Actor agent = Actor.agent("agent-1", "alice");

        assertThatThrownBy(() -> manager.close("s-1", agent))
                .isInstanceOf(AgentIntentMutationException.class);

        // Session is preserved unchanged: it is neither read nor written.
        verify(sessionRepository, never()).save(any());
        assertRejectionRecorded(agent, "s-1");
    }

    @Test
    void agentModifyIsRejectedRecordedAndPreservesSessionUnchanged() {
        Actor agent = Actor.agent("agent-1", "alice");

        assertThatThrownBy(() -> manager.modify("s-1", new IntentChange("hijacked goal"), agent))
                .isInstanceOf(AgentIntentMutationException.class);

        verify(sessionRepository, never()).save(any());
        assertRejectionRecorded(agent, "s-1");
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertRejectionRecorded(Actor agent, String sessionId) {
        ArgumentCaptor<AuditHistoryDocument> captor =
                ArgumentCaptor.forClass(AuditHistoryDocument.class);
        verify(auditHistoryRepository).save(captor.capture());
        AuditHistoryDocument record = captor.getValue();

        assertThat(record.getRecordType())
                .isEqualTo(DefaultIntentSessionManager.RECORD_TYPE_REJECTED_AGENT_INTENT);
        assertThat(record.getReasonCode())
                .isEqualTo(DefaultIntentSessionManager.REASON_AGENT_INTENT_MUTATION_REJECTED);
        assertThat(record.getActorType()).isEqualTo(ActorType.AGENT.name());
        assertThat(record.getUserId()).isEqualTo(agent.userId());
        assertThat(record.getHumanPrincipalId()).isEqualTo(agent.humanPrincipalId());
        assertThat(record.getSessionId()).isEqualTo(sessionId);
        assertThat(record.isIntentPresent()).isFalse();
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getEventId()).isNotBlank();
    }

    private static IntentSessionDocument openDocument(String sessionId, String userId, String intent) {
        IntentSessionDocument document = new IntentSessionDocument();
        document.setSessionId(sessionId);
        document.setUserId(userId);
        document.setDeclaredIntent(intent);
        document.setIntentSource(IntentSource.DECLARED.name());
        document.setStartedAt(NOW - 1000);
        document.setEndedAt(null);
        document.setOpen(true);
        return document;
    }
}
