package com.intentguard.velocity;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

import com.intentguard.domain.CorrectiveAction;

/**
 * The guardrail-facing outcome of evaluating the velocity/rate guardrails for a single
 * Command_Event (Req 5). It is a value object contributed to the guardrail chain; it deliberately
 * does not depend on the shared {@code GuardrailContext} so the stretch guard stays self-contained.
 *
 * @param floor                 the Corrective_Action floor raised by the guard: {@code ALLOW} when
 *                              nothing triggered, or {@code ASK} when the actor's windowed command
 *                              count exceeded the rate limit (Req 5.1)
 * @param scoreFloor            an optional Divergence_Score floor raised by a burst anomaly, equal
 *                              to {@link VelocityConfig#burstAnomalyFloor()} when the inter-command
 *                              interval deviates from the profile mean by more than the burst
 *                              threshold (Req 5.2)
 * @param sessionAnomaly        {@code true} when the windowed rate exceeded the configured velocity
 *                              session-anomaly threshold, signalling a session-anomaly alert
 *                              (Req 5.3)
 * @param triggeredGuardrailIds identifiers of the velocity guardrails that triggered, for the
 *                              Audit_History and Explanation naming (Req 5.4)
 */
public record VelocityResult(
        CorrectiveAction floor,
        OptionalDouble scoreFloor,
        boolean sessionAnomaly,
        List<String> triggeredGuardrailIds) {

    public VelocityResult {
        Objects.requireNonNull(floor, "floor must not be null");
        Objects.requireNonNull(scoreFloor, "scoreFloor must not be null");
        triggeredGuardrailIds = triggeredGuardrailIds == null
                ? List.of()
                : List.copyOf(triggeredGuardrailIds);
    }

    /** A no-op result: no floor raised, no score floor, no session anomaly, nothing triggered. */
    public static VelocityResult none() {
        return new VelocityResult(CorrectiveAction.ALLOW, OptionalDouble.empty(), false, List.of());
    }

    /** Whether this result carries any contribution to the chain (a floor, score floor, or alert). */
    public boolean triggered() {
        return !triggeredGuardrailIds.isEmpty();
    }
}
