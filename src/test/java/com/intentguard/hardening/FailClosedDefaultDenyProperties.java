package com.intentguard.hardening;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-guardrails, Property 27: The engine fails closed when a guardrail dependency
 * is unavailable (Stretch).
 *
 * <p>For any Command_Event for which a required guardrail dependency is unavailable past the
 * configured guardrail decision timeout, the Corrective_Action is {@code BLOCK} (fail-closed) and
 * the fail-closed decision together with the unavailable dependency is recorded in the
 * Audit_History (Validates: Requirements 9.1, 9.2).
 *
 * <p>The {@link FailClosedGuard} is exercised directly with an in-memory Audit_History fake and a
 * fixed {@link Clock} so the property is deterministic. Unavailability is modeled two ways — the
 * dependency does not respond at all, or it responds only after exceeding the timeout budget — and
 * both must yield a fail-closed {@code BLOCK} and a matching audit record naming the dependency.
 */
class FailClosedDefaultDenyProperties {

    private static final long NOW = 1_710_000_000_000L;

    @Property(tries = 200)
    void unavailableDependencyPastTimeoutFailsClosedAndIsRecorded(
            @ForAll("dependencyNames") String dependencyName,
            @ForAll @LongRange(min = 1L, max = 5_000L) long timeoutMs,
            @ForAll boolean unreachable,
            @ForAll @LongRange(min = 1L, max = 100_000L) long overshootMs,
            @ForAll("commandTexts") String commandText) {

        RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
        FailClosedGuard guard = new FailClosedGuard(audit);
        guard.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        // An unavailable dependency: either it never responds, or it responds too slowly.
        ProbeOutcome outcome = unreachable
                ? ProbeOutcome.unreachable(dependencyName)
                : ProbeOutcome.reachable(dependencyName, timeoutMs + overshootMs);
        // Precondition of the property: the dependency is unavailable within the timeout.
        assertThat(outcome.isUnavailableWithin(timeoutMs)).isTrue();

        CommandEvent event = event("evt", commandText);
        Optional<Decision> decision = guard.evaluate(event, () -> outcome, timeoutMs);

        // Fail-closed: the corrective action is BLOCK.
        assertThat(decision).isPresent();
        assertThat(decision.get().action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(decision.get().reasonCode()).isEqualTo(FailClosedGuard.DECISION_REASON);

        // The fail-closed decision and the unavailable dependency are recorded in the Audit_History.
        assertThat(audit.saved).hasSize(1);
        AuditHistoryDocument record = audit.saved.get(0);
        assertThat(record.getRecordType()).isEqualTo(FailClosedGuard.RECORD_TYPE_FAIL_CLOSED);
        assertThat(record.getReasonCode()).isEqualTo(FailClosedGuard.REASON_DEPENDENCY_UNAVAILABLE);
        assertThat(record.getCorrectiveAction()).isEqualTo(CorrectiveAction.BLOCK.name());
        assertThat(record.getEventId()).isEqualTo("evt");
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        // The unavailable dependency is named both structurally and in the explanation.
        assertThat(record.getSignalSource()).isEqualTo(dependencyName);
        assertThat(record.getExplanation()).contains(dependencyName);
    }

    private static CommandEvent event(String eventId, String commandText) {
        return new CommandEvent(
                eventId, Actor.human("alice"), "sess-1", commandText, "/repo", "repo",
                Map.of(), NOW, InputOrigin.TYPED, SignalSource.HOOK, IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    @Provide
    Arbitrary<String> dependencyNames() {
        return Arbitraries.of("datastore", "command-policy-store", "guardrail-config", "llm-service");
    }

    @Provide
    Arbitrary<String> commandTexts() {
        return Arbitraries.of("git push", "rm -rf /data", "kubectl delete ns x", "ls -la");
    }

    /** In-memory Audit_History fake capturing saved records for assertions. */
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
