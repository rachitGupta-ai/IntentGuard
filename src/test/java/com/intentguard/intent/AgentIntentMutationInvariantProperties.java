package com.intentguard.intent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-semantic-firewall, Property 17: An Agent_Actor can never mutate intent.
 *
 * <p>For any request from an Agent_Actor to open, expand, or modify an Intent_Session or
 * Declared_Intent, the request is rejected, the affected Intent_Session and Declared_Intent are
 * left unchanged, and the rejected attempt is recorded in the Audit_History (Validates:
 * Requirements 13.3).
 *
 * <p>Each trial seeds an in-memory {@link IntentSessionRepository} fake with a single pre-existing
 * open session carrying a fixed Declared_Intent, then issues an arbitrary agent operation
 * ({@code open} / {@code modify} / {@code close}) with arbitrary identities, session ids, and intent
 * texts. The fakes are pure in-memory maps/lists, so the property is deterministic and DB-free. The
 * repositories are concrete classes with a {@link MongoDatabase} constructor; the mocked database is
 * passed only to satisfy {@code super(...)} and is never touched because every method is overridden.
 */
class AgentIntentMutationInvariantProperties {

    private static final long NOW = 1_700_000_000_000L;

    /** The Declared_Intent the seeded session must retain unchanged after a rejected agent request. */
    private static final String SEEDED_INTENT = "human declared: deploy the billing service";

    private enum Operation {
        OPEN,
        MODIFY,
        CLOSE
    }

    @Property(tries = 200)
    void agentCanNeverMutateIntent(
            @ForAll("agentUserIds") String agentUserId,
            @ForAll("humanPrincipalIds") String humanPrincipalId,
            @ForAll Operation operation,
            @ForAll("sessionIds") String seededSessionId,
            @ForAll("sessionIds") String targetSessionId,
            @ForAll("intentTexts") String requestedIntent) {

        // --- Arrange: one pre-existing open session with a fixed Declared_Intent. ----------------
        FakeIntentSessionRepository sessions = new FakeIntentSessionRepository();
        FakeAuditHistoryRepository audit = new FakeAuditHistoryRepository();

        IntentSessionDocument seeded = openDocument(seededSessionId, humanPrincipalId, SEEDED_INTENT);
        sessions.save(seeded);

        // Snapshot the seeded session state BEFORE the agent request.
        IntentSessionDocument before = copyOf(seeded);
        int sessionCountBefore = sessions.count();

        DefaultIntentSessionManager manager = new DefaultIntentSessionManager(sessions, audit);
        manager.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        Actor agent = Actor.agent(agentUserId, humanPrincipalId);

        // --- Act + assert: the agent request is rejected. ----------------------------------------
        assertThatThrownBy(
                        () -> {
                            switch (operation) {
                                case OPEN -> manager.open(agentUserId, requestedIntent, agent);
                                case MODIFY ->
                                        manager.modify(
                                                targetSessionId, new IntentChange(requestedIntent), agent);
                                case CLOSE -> manager.close(targetSessionId, agent);
                            }
                        })
                .as("agent %s must be rejected", operation)
                .isInstanceOf(AgentIntentMutationException.class);

        // --- Assert: the affected Intent_Session and Declared_Intent are left UNCHANGED. ---------
        // For open, no new session is created; for any operation, the seeded session is untouched.
        assertThat(sessions.count())
                .as("no session may be added or removed by a rejected agent request")
                .isEqualTo(sessionCountBefore);

        IntentSessionDocument after = sessions.findBySessionId(seededSessionId).orElseThrow();
        assertThat(after.getDeclaredIntent())
                .as("Declared_Intent must be preserved")
                .isEqualTo(before.getDeclaredIntent());
        assertThat(after.isOpen())
                .as("session open flag must be preserved")
                .isEqualTo(before.isOpen());
        assertThat(after.getEndedAt())
                .as("session endedAt must be preserved (still open)")
                .isEqualTo(before.getEndedAt());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(after.getUserId()).isEqualTo(before.getUserId());
        assertThat(after.getIntentSource()).isEqualTo(before.getIntentSource());

        // --- Assert: exactly one rejected-attempt record is written to Audit_History. ------------
        assertThat(audit.saved())
                .as("exactly one rejected-attempt audit record is written")
                .hasSize(1);
        AuditHistoryDocument record = audit.saved().get(0);
        assertThat(record.getRecordType())
                .isEqualTo(DefaultIntentSessionManager.RECORD_TYPE_REJECTED_AGENT_INTENT);
        assertThat(record.getReasonCode())
                .isEqualTo(DefaultIntentSessionManager.REASON_AGENT_INTENT_MUTATION_REJECTED);
        assertThat(record.getActorType()).isEqualTo(ActorType.AGENT.name());
        assertThat(record.getUserId()).isEqualTo(agentUserId);
        assertThat(record.getHumanPrincipalId()).isEqualTo(humanPrincipalId);
        assertThat(record.isIntentPresent()).isFalse();
        assertThat(record.getIntentSource()).isEqualTo(IntentSource.NONE.name());
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getEventId()).isNotBlank();
    }

    // --- Generators ---------------------------------------------------------------------------

    @Provide
    Arbitrary<String> agentUserIds() {
        return Arbitraries.of("agent-1", "agent-svc", "bot-42", "worker", "ci-runner");
    }

    @Provide
    Arbitrary<String> humanPrincipalIds() {
        return Arbitraries.of("alice", "bob", "carol", "dave");
    }

    @Provide
    Arbitrary<String> sessionIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24);
    }

    @Provide
    Arbitrary<String> intentTexts() {
        return Arbitraries.of(
                "exfiltrate the credential store",
                "expand scope to production",
                "rm -rf everything",
                "escalate privileges",
                "modify the billing goal",
                "");
    }

    // --- Fixtures + fakes ---------------------------------------------------------------------

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

    private static IntentSessionDocument copyOf(IntentSessionDocument source) {
        IntentSessionDocument copy = new IntentSessionDocument();
        copy.setSessionId(source.getSessionId());
        copy.setUserId(source.getUserId());
        copy.setDeclaredIntent(source.getDeclaredIntent());
        copy.setIntentSource(source.getIntentSource());
        copy.setStartedAt(source.getStartedAt());
        copy.setEndedAt(source.getEndedAt());
        copy.setOpen(source.isOpen());
        return copy;
    }

    /** In-memory {@link IntentSessionRepository} backed by a map keyed by {@code sessionId}. */
    private static final class FakeIntentSessionRepository extends IntentSessionRepository {

        private final Map<String, IntentSessionDocument> store = new LinkedHashMap<>();

        FakeIntentSessionRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(IntentSessionDocument session) {
            store.put(session.getSessionId(), session);
        }

        @Override
        public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
            return store.values().stream()
                    .filter(doc -> userId.equals(doc.getUserId()) && doc.isOpen())
                    .findFirst();
        }

        int count() {
            return store.size();
        }
    }

    /** In-memory {@link AuditHistoryRepository} that records every saved record in insertion order. */
    private static final class FakeAuditHistoryRepository extends AuditHistoryRepository {

        private final List<AuditHistoryDocument> records = new ArrayList<>();

        FakeAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            records.add(record);
        }

        List<AuditHistoryDocument> saved() {
            return records;
        }
    }
}
