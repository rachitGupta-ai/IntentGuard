package com.intentguard.domain;

import java.util.Objects;

/**
 * The outcome of the Decision Engine for a Command_Event: the chosen Corrective_Action, the
 * Divergence_Score it was based on, and a machine-readable reason code identifying which decision
 * rule applied (e.g. {@code THRESHOLD_ASK}, {@code REJECTED_TAMPER}, {@code LEARNING_CLAMP}).
 *
 * @param action     the corrective action to apply
 * @param score      the Divergence_Score the decision was based on, in [0.0, 1.0]
 * @param reasonCode a short code identifying the decision rule that produced this outcome
 */
public record Decision(CorrectiveAction action, double score, String reasonCode) {

    public Decision {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (score < 0.0 || score > 1.0 || Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0]: " + score);
        }
    }
}
