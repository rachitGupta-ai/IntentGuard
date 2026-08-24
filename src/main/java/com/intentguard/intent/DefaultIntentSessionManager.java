package com.intentguard.intent;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;

/**
 * Default {@link IntentSessionManager}. Human open/close/modify requests are persisted through the
 * {@link IntentSessionRepository}; any Agent_Actor open/expand/modify request is rejected, leaves
 * the affected session unchanged, and is recorded in the Audit_History (Req 4, Req 13.3).
 *
 * <p>The reject-and-record step always runs before (and in place of) any mutation, so a rejected
 * agent request can never alter session state. Session ids are UUIDs; timestamps come from a
 * {@link Clock} that defaults to {@link Clock#systemUTC()} and is overridable in tests.
 */
@Service
public class DefaultIntentSessionManager implements IntentSessionManager {

    /** Audit_History record type for a rejected Agent_Actor intent-mutation attempt (Req 13.3). */
    static final String RECORD_TYPE_REJECTED_AGENT_INTENT = "REJECTED_AGENT_INTENT";

    /** Reason code stamped on a rejected Agent_Actor intent-mutation attempt. */
    static final String REASON_AGENT_INTENT_MUTATION_REJECTED = "AGENT_INTENT_MUTATION_REJECTED";

    private final IntentSessionRepository sessionRepository;
    private final AuditHistoryRepository auditHistoryRepository;
    private volatile Clock clock = Clock.systemUTC();

    public DefaultIntentSessionManager(
            IntentSessionRepository sessionRepository, AuditHistoryRepository auditHistoryRepository) {
        this.sessionRepository = sessionRepository;
        this.auditHistoryRepository = auditHistoryRepository;
    }

    /** Test seam: overrides the clock used to stamp session and audit timestamps. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public IntentSession open(String user, String declaredIntent, Actor actor) {
        return open(user, declaredIntent, null, null, actor);
    }

    @Override
    public IntentSession open(
            String user,
            String declaredIntent,
            String originalDeclaredIntent,
            String declaredIntentLanguageTag,
            Actor actor) {
        if (actor.isAgent()) {
            rejectAgentMutation(actor, null, "open Intent_Session: " + declaredIntent);
        }
        IntentSessionDocument document = new IntentSessionDocument();
        document.setSessionId(UUID.randomUUID().toString());
        document.setUserId(user);
        document.setDeclaredIntent(declaredIntent);
        // Record the untranslated Source_Text and its language tag alongside the English text the
        // engine scores (Req 3.2, 10.4). A blank/null tag defaults to English (Req 7.2, 7.3).
        document.setOriginalDeclaredIntent(originalDeclaredIntent);
        document.setDeclaredIntentLanguageTag(
                declaredIntentLanguageTag == null || declaredIntentLanguageTag.isBlank()
                        ? IntentSession.DEFAULT_LANGUAGE_TAG
                        : declaredIntentLanguageTag);
        document.setIntentSource(IntentSource.DECLARED.name());
        document.setStartedAt(clock.millis());
        document.setEndedAt(null);
        document.setOpen(true);
        sessionRepository.save(document);
        return toDomain(document);
    }

    @Override
    public void close(String sessionId, Actor actor) {
        if (actor.isAgent()) {
            rejectAgentMutation(actor, sessionId, "close Intent_Session");
        }
        IntentSessionDocument document = requireSession(sessionId);
        document.setEndedAt(clock.millis());
        document.setOpen(false);
        sessionRepository.save(document);
    }

    @Override
    public Optional<IntentSession> activeSessionFor(String user) {
        return sessionRepository.findOpenByUserId(user).map(DefaultIntentSessionManager::toDomain);
    }

    @Override
    public void modify(String sessionId, IntentChange change, Actor actor) {
        if (actor.isAgent()) {
            // Reject and record before touching state so the session is preserved unchanged.
            rejectAgentMutation(actor, sessionId, "modify Declared_Intent: " + change.newDeclaredIntent());
        }
        IntentSessionDocument document = requireSession(sessionId);
        document.setDeclaredIntent(change.newDeclaredIntent());
        sessionRepository.save(document);
    }

    private IntentSessionDocument requireSession(String sessionId) {
        return sessionRepository
                .findBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Unknown Intent_Session: " + sessionId));
    }

    /**
     * Records a rejected Agent_Actor intent-mutation attempt in the Audit_History and throws, so the
     * caller never proceeds to mutate any session state (Req 13.3).
     */
    private void rejectAgentMutation(Actor actor, String sessionId, String attempt) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(UUID.randomUUID().toString());
        record.setUserId(actor.userId());
        record.setActorType(ActorType.AGENT.name());
        record.setHumanPrincipalId(actor.humanPrincipalId());
        record.setSessionId(sessionId);
        record.setCommandText(attempt);
        record.setTimestamp(clock.millis());
        record.setRecordType(RECORD_TYPE_REJECTED_AGENT_INTENT);
        record.setReasonCode(REASON_AGENT_INTENT_MUTATION_REJECTED);
        record.setIntentPresent(false);
        record.setIntentSource(IntentSource.NONE.name());
        auditHistoryRepository.save(record);
        throw new AgentIntentMutationException(
                "Agent_Actor may not " + attempt + "; request rejected and recorded (Req 13.3)");
    }

    private static IntentSession toDomain(IntentSessionDocument document) {
        return new IntentSession(
                document.getSessionId(),
                document.getUserId(),
                document.getDeclaredIntent(),
                document.getOriginalDeclaredIntent(),
                document.getDeclaredIntentLanguageTag(),
                document.getIntentSource() == null
                        ? IntentSource.DECLARED
                        : IntentSource.valueOf(document.getIntentSource()),
                document.getStartedAt(),
                document.getEndedAt(),
                document.isOpen());
    }
}
