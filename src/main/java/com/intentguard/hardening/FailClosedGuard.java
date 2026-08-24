package com.intentguard.hardening;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

/**
 * Fail-closed default-deny helper (Req 9.1, 9.2, Stretch).
 *
 * <p>When the Enforcement_Engine cannot reach a decision for a Command_Event within the configured
 * guardrail decision timeout because a required guardrail dependency is unavailable, the engine must
 * apply a {@code BLOCK} as a fail-closed default (Req 9.1) and record the fail-closed decision
 * together with the unavailable dependency in the Audit_History (Req 9.2).
 *
 * <p>This is a pure, testable primitive: given a {@link DependencyProbe} outcome and a timeout, it
 * either returns {@link Optional#empty()} (the dependency was available, so the normal chain may
 * proceed) or an {@link Optional} carrying a terminal fail-closed {@code BLOCK} {@link Decision}
 * after recording the reason. It performs no work at startup, so it is safe to register as a default
 * bean.
 *
 * <p>Fail-closed is itself feature-flagged via
 * {@code intentguard.guardrails.fail-closed.enabled}. Per the design the flag defaults <em>on</em>
 * ({@code matchIfMissing = true}) so the engine ships in the fail-toward-safety posture unless an
 * operator explicitly opts into a permissive posture during bring-up.
 */
@Component
@ConditionalOnProperty(
        name = "intentguard.guardrails.fail-closed.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FailClosedGuard {

    /** Audit {@code recordType} marking a fail-closed default-deny decision. */
    public static final String RECORD_TYPE_FAIL_CLOSED = "FAIL_CLOSED";

    /** Audit {@code reasonCode} for a fail-closed BLOCK caused by an unavailable dependency. */
    public static final String REASON_DEPENDENCY_UNAVAILABLE = "FAIL_CLOSED_DEPENDENCY_UNAVAILABLE";

    /** Reason code carried on the fail-closed {@link Decision} itself. */
    public static final String DECISION_REASON = "FAIL_CLOSED";

    private final AuditHistoryRepository auditHistory;

    /** Injectable clock; drives the audit-record timestamp so tests are deterministic. */
    private Clock clock = Clock.systemUTC();

    public FailClosedGuard(AuditHistoryRepository auditHistory) {
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
    }

    /** Overrides the clock (used by tests for a fixed, deterministic timestamp). */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Evaluates a required guardrail dependency for the given Command_Event and, when that
     * dependency cannot be reached within {@code timeoutMs}, returns a fail-closed {@code BLOCK}
     * decision after recording it (Req 9.1, 9.2). When the dependency is available within the
     * timeout, returns {@link Optional#empty()} so the normal guardrail chain proceeds.
     *
     * @param event     the Command_Event being decided (must not be {@code null})
     * @param probe     the required-dependency availability check (must not be {@code null})
     * @param timeoutMs the configured guardrail decision timeout, in milliseconds
     * @return a fail-closed {@code BLOCK} decision when the dependency is unavailable, else empty
     */
    public Optional<Decision> evaluate(CommandEvent event, DependencyProbe probe, long timeoutMs) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(probe, "probe must not be null");

        ProbeOutcome outcome = probe.probe();
        if (!outcome.isUnavailableWithin(timeoutMs)) {
            return Optional.empty();
        }
        recordFailClosed(event, outcome.dependencyName());
        return Optional.of(failClosedDecision());
    }

    /**
     * The terminal fail-closed decision: a {@code BLOCK} at maximum divergence, tagged with the
     * {@link #DECISION_REASON} reason code. Exposed so callers that already know a required
     * dependency is unavailable can obtain the decision directly.
     *
     * @return a {@code BLOCK} {@link Decision}
     */
    public Decision failClosedDecision() {
        return new Decision(CorrectiveAction.BLOCK, 1.0, DECISION_REASON);
    }

    /**
     * Records a fail-closed decision and the unavailable dependency in the Audit_History (Req 9.2).
     * The unavailable dependency name is captured both in a structured field ({@code signalSource})
     * and in the human-readable explanation so it is queryable and reviewable.
     *
     * @param event          the Command_Event that was blocked fail-closed
     * @param dependencyName the required guardrail dependency that was unavailable
     */
    public void recordFailClosed(CommandEvent event, String dependencyName) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(dependencyName, "dependencyName must not be null");

        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(event.eventId());
        record.setUserId(event.userId());
        record.setActorType(event.actorType().name());
        record.setSessionId(event.sessionId());
        record.setCommandText(event.commandText());
        record.setCwd(event.cwd());
        record.setRepo(event.repo());
        record.setTimestamp(clock.withZone(ZoneOffset.UTC).millis());
        record.setDivergenceScore(1.0);
        record.setCorrectiveAction(CorrectiveAction.BLOCK.name());
        record.setRecordType(RECORD_TYPE_FAIL_CLOSED);
        record.setReasonCode(REASON_DEPENDENCY_UNAVAILABLE);
        // Persist the unavailable dependency in a structured field for querying...
        record.setSignalSource(dependencyName);
        // ...and name it in the explanation for review.
        record.setExplanation(
                "Fail-closed BLOCK: required guardrail dependency '"
                        + dependencyName
                        + "' was unavailable within the guardrail decision timeout.");
        auditHistory.save(record);
    }
}
