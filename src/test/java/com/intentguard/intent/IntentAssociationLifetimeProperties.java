package com.intentguard.intent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Feature: intentguard-semantic-firewall, Property 8: Intent association follows session lifetime
 *
 * <p>For any user with an open Intent_Session, every subsequent Command_Event from that user is
 * associated with that session; after the session is closed (end timestamp recorded), no new
 * Command_Event is associated with it, and events with no open session are scored with intent
 * absent and that absence recorded.
 *
 * <p>Validates: Requirements 4.2, 4.3, 4.4
 *
 * <p>The property drives many open/close cycles through the real {@link DefaultIntentSessionManager}
 * backed by a deterministic, DB-free in-memory {@link IntentSessionRepository} fake. "Association at
 * event time" is modeled exactly as the ingestion/scoring pipeline resolves it: an event is
 * associated with the session returned by {@link IntentSessionManager#activeSessionFor(String)} at
 * the moment the event is observed. When a session is open the event is scored under that session's
 * Declared_Intent ({@link IntentSource#DECLARED}); when none is open the event is scored with intent
 * absent ({@link IntentSource#NONE}) and that absence is recorded on the event score.
 */
class IntentAssociationLifetimeProperties {

    /**
     * Result of resolving intent association for a single Command_Event at the time it is observed.
     * Mirrors how the pipeline tags an event: {@code sessionId} is the associated session (or
     * {@code null}), {@code intentSource} is DECLARED when a session is open and NONE otherwise, and
     * {@code intentPresent} records whether a Declared_Intent was in force (Req 4.2, 4.4).
     */
    private record EventScore(String sessionId, String declaredIntent, IntentSource intentSource, boolean intentPresent) {}

    /**
     * Models exactly what the Signal_Ingestor / Scoring pipeline does to associate an event: it asks
     * the manager for the user's active session and tags the event accordingly (Req 4.2, 4.4).
     */
    private static EventScore scoreEvent(IntentSessionManager manager, String user) {
        return manager
                .activeSessionFor(user)
                .map(s -> new EventScore(s.sessionId(), s.declaredIntent(), IntentSource.DECLARED, true))
                .orElse(new EventScore(null, null, IntentSource.NONE, false));
    }

    @Property(tries = 200)
    void intentAssociationFollowsSessionLifetime(
            @ForAll("users") String user,
            @ForAll("intents") String declaredIntent,
            @ForAll @IntRange(min = 0, max = 8) int eventsBeforeOpen,
            @ForAll @IntRange(min = 0, max = 8) int eventsWhileOpen,
            @ForAll @IntRange(min = 0, max = 8) int eventsAfterClose) {

        InMemoryIntentSessionRepository sessions = new InMemoryIntentSessionRepository();
        AuditHistoryRepository audit = mock(AuditHistoryRepository.class);
        DefaultIntentSessionManager manager = new DefaultIntentSessionManager(sessions, audit);
        Actor human = Actor.human(user);

        // --- Phase 1: no session open yet -> events are scored with intent absent (Req 4.4) -----
        assertThat(manager.activeSessionFor(user)).isEmpty();
        for (int i = 0; i < eventsBeforeOpen; i++) {
            EventScore score = scoreEvent(manager, user);
            assertThat(score.intentPresent()).isFalse();
            assertThat(score.intentSource()).isEqualTo(IntentSource.NONE);
            assertThat(score.sessionId()).isNull();
        }

        // --- Phase 2: open a session; every subsequent event associates with it (Req 4.2) -------
        IntentSession opened = manager.open(user, declaredIntent, human);
        assertThat(opened.open()).isTrue();
        assertThat(opened.endedAt()).isNull();

        for (int i = 0; i < eventsWhileOpen; i++) {
            EventScore score = scoreEvent(manager, user);
            assertThat(score.intentPresent()).isTrue();
            assertThat(score.intentSource()).isEqualTo(IntentSource.DECLARED);
            // Associated with exactly the open session and its Declared_Intent, stably across events.
            assertThat(score.sessionId()).isEqualTo(opened.sessionId());
            assertThat(score.declaredIntent()).isEqualTo(declaredIntent);
        }

        // --- Phase 3: close the session; end timestamp is recorded and it is no longer open ------
        manager.close(opened.sessionId(), human);

        IntentSessionDocument closed = sessions.findBySessionId(opened.sessionId()).orElseThrow();
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.getEndedAt()).isNotNull();

        // No open session remains for the user (Req 4.3).
        assertThat(manager.activeSessionFor(user)).isEmpty();

        // --- Phase 4: post-close events do not associate with the closed session; intent absent --
        for (int i = 0; i < eventsAfterClose; i++) {
            EventScore score = scoreEvent(manager, user);
            assertThat(score.intentPresent()).isFalse();
            assertThat(score.intentSource()).isEqualTo(IntentSource.NONE);
            assertThat(score.sessionId()).isNull();
            // In particular, never re-associated with the now-closed session.
            assertThat(score.sessionId()).isNotEqualTo(opened.sessionId());
        }
    }

    // ----- Generators ---------------------------------------------------------------------------

    @Provide
    Arbitrary<String> users() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> intents() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(40);
    }

    // ----- In-memory repository fake ------------------------------------------------------------

    /**
     * Deterministic, DB-free {@link IntentSessionRepository} backed by a map keyed on
     * {@code sessionId}. Overrides every method the manager uses so no live Mongo collection is
     * touched; the superclass constructor is satisfied with a mock {@link MongoDatabase} whose
     * {@code getCollection} return value is never used.
     */
    private static final class InMemoryIntentSessionRepository extends IntentSessionRepository {

        private final Map<String, IntentSessionDocument> bySessionId = new HashMap<>();

        InMemoryIntentSessionRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(IntentSessionDocument session) {
            bySessionId.put(session.getSessionId(), session);
        }

        @Override
        public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
            return bySessionId.values().stream()
                    .filter(doc -> userId.equals(doc.getUserId()) && doc.isOpen())
                    .findFirst();
        }
    }
}
