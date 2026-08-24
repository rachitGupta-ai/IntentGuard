package com.intentguard.blastradius;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

import com.intentguard.domain.CorrectiveAction;

/**
 * The guardrail-facing outcome of evaluating the blast-radius / protected-target guardrails for a
 * single Command_Event, consumed by the {@code GuardrailDecisionEngine} chain (Req 3).
 *
 * @param floor                the Corrective_Action floor raised by the guard: {@code ALLOW} when
 *                             nothing triggered, or {@code ASK} when a protected-target access,
 *                             mass-operation breach, or indeterminate evaluation raised it
 *                             (Req 3.2, 3.4, 3.5, 3.8)
 * @param blockOnAccessHit     {@code true} when a block-on-access {@link ProtectedTarget} was
 *                             touched, which the chain treats as a short-circuit {@code BLOCK}
 *                             (Req 3.3)
 * @param scoreFloor           an optional Divergence_Score floor (for example {@code 0.90}) raised
 *                             by a destructive-verb match and fed to the threshold map (Req 3.6)
 * @param indeterminate        {@code true} when the blast radius or protected-target access could
 *                             not be determined, recorded for audit (Req 3.8)
 * @param triggeredGuardrailIds identifiers of the guardrails/targets that triggered, for the
 *                             Audit_History and Explanation (Req 3.7)
 */
public record BlastRadiusResult(
        CorrectiveAction floor,
        boolean blockOnAccessHit,
        OptionalDouble scoreFloor,
        boolean indeterminate,
        List<String> triggeredGuardrailIds) {

    public BlastRadiusResult {
        Objects.requireNonNull(floor, "floor must not be null");
        Objects.requireNonNull(scoreFloor, "scoreFloor must not be null");
        triggeredGuardrailIds = triggeredGuardrailIds == null
                ? List.of()
                : List.copyOf(triggeredGuardrailIds);
    }

    /** A no-op result: no floor raised, no block-on-access hit, no score floor, nothing triggered. */
    public static BlastRadiusResult none() {
        return new BlastRadiusResult(
                CorrectiveAction.ALLOW, false, OptionalDouble.empty(), false, List.of());
    }
}
