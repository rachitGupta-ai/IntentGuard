package com.intentguard.intent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.ingest.ShellSignalNormalizer;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-semantic-firewall, Property 9: Ingestion preserves provided fields
 *
 * <p>For any Shell_Hook signal or Declared_Intent submission, the resulting Command_Event or
 * Intent_Session preserves all provided fields (command text, cwd, environment context, user
 * identity, timestamp, typed-vs-pasted indicator; intent text, user, start timestamp), and a
 * Command_Event lacking a typed-vs-pasted indicator records it as UNKNOWN while still being
 * processed.
 *
 * <p>Validates: Requirements 2.2, 2.4, 4.1
 *
 * <p>Two facets are exercised in the single property, one per provided-field carrier:
 *
 * <ul>
 *   <li><b>Shell_Hook facet (Req 2.2, 2.4):</b> an arbitrary {@link RawShellSignal} (human or
 *       agent actor, arbitrary command text, cwd, environment map, timestamp, and an
 *       {@link InputOrigin} that may be {@code null}) is normalized by the real
 *       {@link ShellSignalNormalizer}. The produced {@link CommandEvent} must preserve every
 *       provided field, and a {@code null} typed-vs-pasted indicator must be recorded as
 *       {@link InputOrigin#UNKNOWN} with the event still produced (non-null).
 *   <li><b>Declared_Intent facet (Req 4.1):</b> an arbitrary Declared_Intent submission (user,
 *       intent text, start timestamp via an injected fixed {@link Clock}) opened through the real
 *       {@link DefaultIntentSessionManager} must yield an {@link IntentSession} preserving the
 *       intent text, user, and start timestamp.
 * </ul>
 *
 * <p>The intent facet is DB-free: it uses a deterministic in-memory {@link IntentSessionRepository}
 * fake and a fixed {@link Clock}, mirroring {@code IntentAssociationLifetimeProperties}.
 */
class IngestionFieldPreservationProperties {

    @Property(tries = 200)
    void ingestionPreservesProvidedFields(
            @ForAll("shellSignals") RawShellSignal signal,
            @ForAll("users") String user,
            @ForAll("intents") String declaredIntent,
            @ForAll @LongRange(min = 0L, max = 4_000_000_000_000L) long startMillis) {

        // --- Facet A: Shell_Hook signal normalization preserves all provided fields (Req 2.2) ----
        ShellSignalNormalizer normalizer = new ShellSignalNormalizer(() -> "fixed-event-id");
        CommandEvent event = normalizer.normalize(signal);

        // The event is always produced, even when the indicator is missing (Req 2.4).
        assertThat(event).isNotNull();
        assertThat(event.eventId()).isEqualTo("fixed-event-id");

        // Every provided field is preserved verbatim.
        assertThat(event.actor()).isEqualTo(signal.actor());
        assertThat(event.userId()).isEqualTo(signal.actor().userId());
        assertThat(event.commandText()).isEqualTo(signal.commandText());
        assertThat(event.cwd()).isEqualTo(signal.cwd());
        assertThat(event.timestamp()).isEqualTo(signal.timestamp());
        // envContext is preserved with equal contents (RawShellSignal already made an immutable copy).
        assertThat(event.envContext()).containsExactlyInAnyOrderEntriesOf(signal.envContext());

        // A missing typed-vs-pasted indicator is recorded as UNKNOWN; otherwise preserved (Req 2.4).
        if (signal.inputOrigin() == null) {
            assertThat(event.inputOrigin()).isEqualTo(InputOrigin.UNKNOWN);
        } else {
            assertThat(event.inputOrigin()).isEqualTo(signal.inputOrigin());
        }

        // --- Facet B: Declared_Intent submission preserves intent, user, start timestamp (Req 4.1)-
        InMemoryIntentSessionRepository sessions = new InMemoryIntentSessionRepository();
        AuditHistoryRepository audit = mock(AuditHistoryRepository.class);
        DefaultIntentSessionManager manager = new DefaultIntentSessionManager(sessions, audit);
        // Injected fixed clock fixes the recorded start timestamp deterministically.
        manager.setClock(Clock.fixed(Instant.ofEpochMilli(startMillis), ZoneOffset.UTC));

        IntentSession opened = manager.open(user, declaredIntent, Actor.human(user));

        assertThat(opened.declaredIntent()).isEqualTo(declaredIntent);
        assertThat(opened.userId()).isEqualTo(user);
        assertThat(opened.startedAt()).isEqualTo(startMillis);
        // A freshly opened session is open with no end timestamp.
        assertThat(opened.open()).isTrue();
        assertThat(opened.endedAt()).isNull();

        // The preserved fields survive the persistence round-trip through the repository fake.
        IntentSessionDocument persisted = sessions.findBySessionId(opened.sessionId()).orElseThrow();
        assertThat(persisted.getDeclaredIntent()).isEqualTo(declaredIntent);
        assertThat(persisted.getUserId()).isEqualTo(user);
        assertThat(persisted.getStartedAt()).isEqualTo(startMillis);
    }

    // ----- Generators ---------------------------------------------------------------------------

    @Provide
    Arbitrary<RawShellSignal> shellSignals() {
        Arbitrary<Actor> actors = actors();
        Arbitrary<String> commandTexts = Arbitraries.strings().ofMinLength(0).ofMaxLength(60);
        Arbitrary<String> cwds =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(24).map(s -> "/" + s);
        Arbitrary<Map<String, String>> envs = envContexts();
        Arbitrary<Long> timestamps = Arbitraries.longs().between(0L, 4_000_000_000_000L);
        // InputOrigin including null (~25% of the time) to exercise the UNKNOWN mapping (Req 2.4).
        Arbitrary<InputOrigin> origins = Arbitraries.of(InputOrigin.class).injectNull(0.25);

        return Combinators.combine(actors, commandTexts, cwds, envs, timestamps, origins)
                .as(RawShellSignal::new);
    }

    private Arbitrary<Actor> actors() {
        Arbitrary<String> userIds =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Actor> humans = userIds.map(Actor::human);
        Arbitrary<Actor> agents = Combinators.combine(userIds, userIds).as(Actor::agent);
        return Arbitraries.oneOf(humans, agents);
    }

    private Arbitrary<Map<String, String>> envContexts() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
        Arbitrary<String> values = Arbitraries.strings().alpha().numeric().ofMinLength(0).ofMaxLength(16);
        return Arbitraries.maps(keys, values).ofMaxSize(5);
    }

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
     * {@code sessionId}, mirroring the fake in {@code IntentAssociationLifetimeProperties}. The
     * superclass constructor is satisfied with a mock {@link MongoDatabase} whose collection is
     * never used.
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
