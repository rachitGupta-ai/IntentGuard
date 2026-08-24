package com.intentguard.ingest;

import java.util.List;
import java.util.Objects;

import com.intentguard.domain.CommandEvent;

/**
 * The outcome of correlating a batch of Shell_Hook events with Audit_Feed events (Req 2.3).
 *
 * @param correlated   matched hook/audit pairs (the hook side promoted to {@code CORRELATED})
 * @param hookBypasses audit-only events with no matching hook record — each indicates a bypass of
 *                     the blocking gate ({@code sourceOnly=AUDIT})
 * @param unmatchedHooks hook events that were not matched to any audit event (e.g. the audit
 *                     record has not yet arrived, or the command was blocked pre-execution)
 */
public record CorrelationResult(
        List<CorrelatedPair> correlated,
        List<CommandEvent> hookBypasses,
        List<CommandEvent> unmatchedHooks) {

    public CorrelationResult {
        correlated = List.copyOf(Objects.requireNonNull(correlated, "correlated must not be null"));
        hookBypasses = List.copyOf(Objects.requireNonNull(hookBypasses, "hookBypasses must not be null"));
        unmatchedHooks =
                List.copyOf(Objects.requireNonNull(unmatchedHooks, "unmatchedHooks must not be null"));
    }

    /** Whether any audit-only event bypassed the blocking gate. */
    public boolean hasHookBypass() {
        return !hookBypasses.isEmpty();
    }
}
