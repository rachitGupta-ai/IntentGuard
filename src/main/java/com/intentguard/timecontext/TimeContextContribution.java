package com.intentguard.timecontext;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

import com.intentguard.domain.CorrectiveAction;

/**
 * The guardrail-facing outcome of evaluating the time/context guardrails for a single
 * Command_Event (Req 7). It is a self-contained contribution — mirroring the shape other guards
 * feed into the guardrail chain — carrying a Corrective_Action floor, an optional Divergence_Score
 * floor, whether the session's source was restricted, and the ids of the guardrails that triggered.
 *
 * @param floor                 the Corrective_Action floor raised by the guard: {@code ALLOW} when
 *                              nothing triggered, or {@code ASK} when an off-window risky event
 *                              (Req 7.1) or an unapproved source (Req 7.3) raised it
 * @param scoreFloor            an optional Divergence_Score floor raised by a context-mismatch rule
 *                              (Req 7.2); empty when no rule was violated
 * @param sourceRestricted      {@code true} when the session originated from a source not on the
 *                              approved-source list, recorded for the Audit_History (Req 7.4)
 * @param triggeredGuardrailIds identifiers of the guardrails/rules that triggered, for the
 *                              Audit_History and Explanation
 */
public record TimeContextContribution(
        CorrectiveAction floor,
        OptionalDouble scoreFloor,
        boolean sourceRestricted,
        List<String> triggeredGuardrailIds) {

    public TimeContextContribution {
        Objects.requireNonNull(floor, "floor must not be null");
        Objects.requireNonNull(scoreFloor, "scoreFloor must not be null");
        triggeredGuardrailIds = triggeredGuardrailIds == null
                ? List.of()
                : List.copyOf(triggeredGuardrailIds);
    }

    /** A no-op contribution: no floor raised, no score floor, no source restriction. */
    public static TimeContextContribution none() {
        return new TimeContextContribution(
                CorrectiveAction.ALLOW, OptionalDouble.empty(), false, List.of());
    }
}
