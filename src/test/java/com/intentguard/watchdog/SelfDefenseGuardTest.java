package com.intentguard.watchdog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link SelfDefenseGuard} backed by an in-memory {@link AuditHistoryRepository}
 * (no live MongoDB). Cover Req 1.3: an unprivileged monitored user's stop/pause/reconfigure attempt
 * is rejected, the engine's process/configuration state is preserved unchanged, and a rejected
 * attempt is recorded; and Req 1.6: a socket request targeting the engine's control surface is
 * rejected and recorded while a benign request is allowed.
 */
class SelfDefenseGuardTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Actor MONITORED_USER = Actor.human("mallory");

    private InMemoryAuditRepository repository;
    private SelfDefenseGuard guard;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditRepository();
        guard = new SelfDefenseGuard(repository, new TamperClassifier());
        guard.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // --- Req 1.3: unprivileged control attempts are rejected, state preserved, recorded ---------

    @Test
    void unprivilegedStopIsRejectedStatePreservedAndRecorded() {
        assertRejectedPreservedAndRecorded(ControlOperation.STOP);
    }

    @Test
    void unprivilegedPauseIsRejectedStatePreservedAndRecorded() {
        assertRejectedPreservedAndRecorded(ControlOperation.PAUSE);
    }

    @Test
    void unprivilegedReconfigureIsRejectedStatePreservedAndRecorded() {
        assertRejectedPreservedAndRecorded(ControlOperation.RECONFIGURE);
    }

    private void assertRejectedPreservedAndRecorded(ControlOperation operation) {
        assertThat(guard.isRunning()).isTrue();
        assertThat(guard.isPaused()).isFalse();

        assertThatThrownBy(() -> guard.handleControlRequest(MONITORED_USER, operation, false))
                .isInstanceOf(ControlRequestRejectedException.class);

        // Configuration and process state are preserved unchanged.
        assertThat(guard.isRunning()).isTrue();
        assertThat(guard.isPaused()).isFalse();

        // The rejected attempt is recorded in the Audit_History.
        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        AuditHistoryDocument record = records.get(0);
        assertThat(record.getRecordType()).isEqualTo(SelfDefenseGuard.RECORD_TYPE_REJECTED_TAMPER);
        assertThat(record.getReasonCode())
                .isEqualTo(SelfDefenseGuard.REASON_UNPRIVILEGED_CONTROL_REJECTED);
        assertThat(record.getUserId()).isEqualTo(MONITORED_USER.userId());
        assertThat(record.getActorType()).isEqualTo(ActorType.HUMAN.name());
        assertThat(record.getCommandText()).contains(operation.name());
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getDivergenceScore()).isEqualTo(1.0);
        assertThat(record.getEventId()).isNotBlank();
    }

    @Test
    void privilegedStopStopsTheEngineAndRecordsNothing() {
        guard.handleControlRequest(Actor.human("root"), ControlOperation.STOP, true);

        assertThat(guard.isRunning()).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void privilegedPausePausesTheEngineAndRecordsNothing() {
        guard.handleControlRequest(Actor.human("root"), ControlOperation.PAUSE, true);

        assertThat(guard.isPaused()).isTrue();
        assertThat(guard.isRunning()).isTrue();
        assertThat(repository.findAll()).isEmpty();
    }

    // --- Req 1.6: socket requests targeting the control surface are rejected and recorded --------

    @Test
    void socketRequestTargetingDatastoreIsRejectedAndRecorded() {
        CommandEvent tamper = event("mongo intentguard.audit_history --eval 'db.dropDatabase()'");

        assertThatThrownBy(() -> guard.handleSocketRequest(tamper, MONITORED_USER))
                .isInstanceOf(ControlRequestRejectedException.class);

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getRecordType())
                .isEqualTo(SelfDefenseGuard.RECORD_TYPE_REJECTED_TAMPER);
        assertThat(records.get(0).getReasonCode())
                .isEqualTo(SelfDefenseGuard.REASON_TAMPER_SOCKET_REQUEST_REJECTED);
        assertThat(records.get(0).getCommandText()).isEqualTo(tamper.commandText());
    }

    @Test
    void benignSocketRequestIsAllowedAndNotRecorded() {
        CommandEvent benign = event("git status");

        guard.handleSocketRequest(benign, MONITORED_USER);

        assertThat(repository.findAll()).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    private static CommandEvent event(String commandText) {
        return new CommandEvent(
                "evt-1",
                MONITORED_USER,
                null,
                commandText,
                "/home/mallory",
                null,
                Map.of(),
                NOW,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);
    }

    /**
     * In-memory {@link AuditHistoryRepository} that records saved documents without touching Mongo.
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
