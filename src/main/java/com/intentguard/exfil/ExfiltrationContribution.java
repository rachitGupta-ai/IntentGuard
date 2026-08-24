package com.intentguard.exfil;

import java.util.List;
import java.util.Objects;

import com.intentguard.domain.CorrectiveAction;

/**
 * The guardrail-facing outcome of evaluating the data-exfiltration guardrails for a single
 * Command_Event, consumed by the guardrail chain (Req 6).
 *
 * @param floor                 the Corrective_Action floor raised by the guard: {@code ALLOW} when
 *                              nothing triggered, {@code ASK} for unapproved egress or a correlated
 *                              secret+egress alert, or {@code BLOCK} on a canary hit (Req 6.1, 6.3,
 *                              6.4)
 * @param correlatedExfilAlert  {@code true} when a secret/credential access was followed within the
 *                              correlation window by an outbound connection in the same session
 *                              (Req 6.2, 6.3)
 * @param canaryHit             {@code true} when a canary token was accessed; the chain treats this
 *                              as a short-circuit {@code BLOCK} (Req 6.4)
 * @param highRiskAlert         {@code true} when a high-risk alert must be raised on the
 *                              Control_Tower (a canary hit) (Req 6.5)
 * @param triggeredGuardrailIds identifiers of the guardrails/tokens that triggered, for the
 *                              Audit_History and Explanation
 * @param recordedDestinations  the unapproved egress destination(s) recorded for the Audit_History
 *                              (Req 6.1)
 */
public record ExfiltrationContribution(
        CorrectiveAction floor,
        boolean correlatedExfilAlert,
        boolean canaryHit,
        boolean highRiskAlert,
        List<String> triggeredGuardrailIds,
        List<String> recordedDestinations) {

    /** Trigger id recorded when unapproved egress raises the floor (Req 6.1). */
    public static final String UNAPPROVED_EGRESS_TRIGGER_ID = "unapproved-egress";

    /** Trigger id recorded when a secret-then-egress correlation raises the floor (Req 6.2, 6.3). */
    public static final String CORRELATED_EXFIL_TRIGGER_ID = "correlated-exfiltration";

    public ExfiltrationContribution {
        Objects.requireNonNull(floor, "floor must not be null");
        triggeredGuardrailIds = triggeredGuardrailIds == null
                ? List.of()
                : List.copyOf(triggeredGuardrailIds);
        recordedDestinations = recordedDestinations == null
                ? List.of()
                : List.copyOf(recordedDestinations);
    }

    /** A no-op result: no floor raised, no alert, nothing triggered. */
    public static ExfiltrationContribution none() {
        return new ExfiltrationContribution(
                CorrectiveAction.ALLOW, false, false, false, List.of(), List.of());
    }
}
