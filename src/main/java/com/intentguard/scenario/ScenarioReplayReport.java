package com.intentguard.scenario;

import java.util.List;
import java.util.Objects;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;

/**
 * The ordered result of replaying a whole scenario from its frozen baseline (Req 16.1, 16.2).
 * Carries the scenario id, the Threshold_Configuration that was applied for the replay, and the
 * per-event {@link ScenarioReplayResult}s in script order. Two replays of the same scenario from
 * the same baseline produce equal reports (determinism, Property 20).
 *
 * @param scenarioId       the replayed scenario's id
 * @param appliedThresholds the frozen Threshold_Configuration reset and applied for this replay
 * @param results          the per-event outcomes, in the scripted order
 */
public record ScenarioReplayReport(
        String scenarioId,
        ThresholdConfiguration appliedThresholds,
        List<ScenarioReplayResult> results) {

    public ScenarioReplayReport {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(appliedThresholds, "appliedThresholds must not be null");
        results = List.copyOf(results);
    }

    /** The ordered Corrective_Actions produced by the replay, one per scripted event. */
    public List<CorrectiveAction> actions() {
        return results.stream().map(ScenarioReplayResult::action).toList();
    }
}
