package com.intentguard.scenario;

import java.util.Objects;

import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.DivergenceResult;

/**
 * The outcome of replaying a single scripted Command_Event in a scenario (Req 16). Captures enough
 * to assert the expected Corrective_Action, explanation, and score breakdown for the demo scenarios
 * (Tasks 17.3, 17.4).
 *
 * @param eventId         the replayed event's id
 * @param commandText     the replayed command text
 * @param action          the Corrective_Action the decision pipeline produced
 * @param reasonCode      the decision reason code (e.g. {@code THRESHOLD_BLOCK})
 * @param divergenceScore the Divergence_Score the decision was based on, in {@code [0,1]}
 * @param explanation     the plain-English explanation for an {@code ASK}/{@code BLOCK}, or
 *                        {@code null} for an {@code ALLOW}
 * @param divergence      the full scoring result (component scores, applied weights, exclusions)
 */
public record ScenarioReplayResult(
        String eventId,
        String commandText,
        CorrectiveAction action,
        String reasonCode,
        double divergenceScore,
        String explanation,
        DivergenceResult divergence) {

    public ScenarioReplayResult {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(commandText, "commandText must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(divergence, "divergence must not be null");
    }
}
