package com.intentguard.hardening;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link FailClosedGuard} covering the available-dependency passthrough and the
 * unavailable-dependency fail-closed BLOCK + audit path (Req 9.1, 9.2).
 */
class FailClosedGuardTest {

    private static final long NOW = 1_710_000_000_000L;

    private final RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
    private final FailClosedGuard guard = newGuard();

    private FailClosedGuard newGuard() {
        FailClosedGuard g = new FailClosedGuard(audit);
        g.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return g;
    }

    @Test
    void availableDependencyWithinTimeoutPassesThroughWithoutBlockingOrRecording() {
        Optional<Decision> decision =
                guard.evaluate(event(), () -> ProbeOutcome.reachable("datastore", 50L), 2_000L);

        assertThat(decision).isEmpty();
        assertThat(audit.saved).isEmpty();
    }

    @Test
    void unreachableDependencyFailsClosedAndRecordsUnavailableDependency() {
        Optional<Decision> decision =
                guard.evaluate(event(), () -> ProbeOutcome.unreachable("datastore"), 2_000L);

        assertThat(decision).isPresent();
        assertThat(decision.get().action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(audit.saved).hasSize(1);
        assertThat(audit.saved.get(0).getSignalSource()).isEqualTo("datastore");
        assertThat(audit.saved.get(0).getReasonCode())
                .isEqualTo(FailClosedGuard.REASON_DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void slowDependencyPastTimeoutFailsClosed() {
        // Responds, but only after exceeding the 2s decision budget.
        Optional<Decision> decision =
                guard.evaluate(event(), () -> ProbeOutcome.reachable("llm-service", 2_001L), 2_000L);

        assertThat(decision).isPresent();
        assertThat(decision.get().action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(audit.saved).hasSize(1);
        assertThat(audit.saved.get(0).getSignalSource()).isEqualTo("llm-service");
    }

    private static CommandEvent event() {
        return new CommandEvent(
                "evt", Actor.human("alice"), "sess-1", "rm -rf /data", "/repo", "repo",
                Map.of(), NOW, InputOrigin.TYPED, SignalSource.HOOK, IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    private static final class RecordingAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> saved = new ArrayList<>();

        RecordingAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            saved.add(record);
        }
    }
}
