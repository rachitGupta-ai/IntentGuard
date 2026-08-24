package com.intentguard.watchdog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.Actor;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-semantic-firewall, Property 5: Unprivileged control attempts preserve state
 * and are recorded
 *
 * <p>For any stop, pause, or reconfigure request issued by an actor without privilege over the
 * Enforcement_Engine, the engine's configuration and process state are left unchanged and a
 * rejected-attempt record is persisted to the Audit_History.
 *
 * <p>Validates: Requirements 1.3
 *
 * <p>No live MongoDB is available in this environment, so the Audit_History is exercised through an
 * in-memory {@link AuditHistoryRepository} fake (constructed over a mocked {@link MongoDatabase}
 * with {@code save}/{@code findAll} overridden), mirroring the pattern established by
 * {@code SelfDefenseGuardTest}.
 */
class UnprivilegedControlProperties {

    private static final long NOW = 1_700_000_000_000L;

    @Property(tries = 200)
    void unprivilegedControlRequestPreservesStateAndIsRecorded(
            @ForAll("unprivilegedActors") Actor actor,
            @ForAll ControlOperation operation) {
        InMemoryAuditRepository repository = new InMemoryAuditRepository();
        SelfDefenseGuard guard = new SelfDefenseGuard(repository, new TamperClassifier());
        guard.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        // Capture the engine's configuration / process state before the attempt.
        boolean runningBefore = guard.isRunning();
        boolean pausedBefore = guard.isPaused();

        // An actor WITHOUT privilege issuing any control operation must be rejected.
        assertThatThrownBy(() -> guard.handleControlRequest(actor, operation, false))
                .isInstanceOf(ControlRequestRejectedException.class);

        // The engine's configuration / process state is left unchanged.
        assertThat(guard.isRunning()).isEqualTo(runningBefore);
        assertThat(guard.isPaused()).isEqualTo(pausedBefore);

        // Exactly one rejected-attempt record is persisted to the Audit_History with the rejected
        // record type and reason code, attributed to the requesting actor and the attempted op.
        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        AuditHistoryDocument record = records.get(0);
        assertThat(record.getRecordType()).isEqualTo(SelfDefenseGuard.RECORD_TYPE_REJECTED_TAMPER);
        assertThat(record.getReasonCode())
                .isEqualTo(SelfDefenseGuard.REASON_UNPRIVILEGED_CONTROL_REJECTED);
        assertThat(record.getUserId()).isEqualTo(actor.userId());
        assertThat(record.getActorType()).isEqualTo(actor.type().name());
        assertThat(record.getCommandText()).contains(operation.name());
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getDivergenceScore()).isEqualTo(1.0);
        assertThat(record.getEventId()).isNotBlank();
    }

    // ----- Generators ---------------------------------------------------------------------------

    /** Non-empty identifiers usable as OS user / principal ids. */
    private Arbitrary<String> ids() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
    }

    /**
     * Arbitrary actors that lack privilege over the engine: both human monitored users and AI
     * agents bound to a human principal. Privilege is expressed separately via the
     * {@code actorHasPrivilege=false} argument to {@code handleControlRequest}.
     */
    @Provide
    Arbitrary<Actor> unprivilegedActors() {
        Arbitrary<Actor> humans = ids().map(Actor::human);
        Arbitrary<Actor> agents =
                Combinators.combine(ids(), ids()).as(Actor::agent);
        return Arbitraries.oneOf(humans, agents);
    }

    /**
     * In-memory {@link AuditHistoryRepository} that records saved documents without touching Mongo,
     * mirroring the fake used by {@code SelfDefenseGuardTest}.
     */
    private static final class InMemoryAuditRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> store = new ArrayList<>();

        InMemoryAuditRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            store.add(record);
        }

        @Override
        public List<AuditHistoryDocument> findAll() {
            return new ArrayList<>(store);
        }
    }
}
