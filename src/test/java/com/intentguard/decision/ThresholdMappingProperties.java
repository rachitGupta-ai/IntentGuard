package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 3: Threshold mapping is total and monotonic.
 *
 * <p>For any Divergence_Score in [0,1] and any valid Threshold_Configuration, the mapping yields
 * exactly one Corrective_Action — allow when the score is below the ask threshold, ask when it is
 * in the ask range, and block when it is at or above the block threshold — and a higher score never
 * maps to a less restrictive action (Validates: Requirements 7.1, 7.2, 7.3, 7.4).
 *
 * <p>The property is exercised against {@link DefaultDecisionEngine#decide} on a non-tamper HUMAN
 * Command_Event with an {@code ACTIVE} profile and an open human session. This isolates the pure
 * threshold map (Rule 2) from the tamper override, learning clamp, and agent-containment rules:
 * the command text is a benign, non-tamper command, so none of those adjustments fire and the
 * returned action reflects only the Divergence_Score-to-threshold mapping.
 */
class ThresholdMappingProperties {

    private final DefaultDecisionEngine engine = new DefaultDecisionEngine(new TamperClassifier());

    @Property(tries = 200)
    void thresholdMappingIsTotalAndMonotonic(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double thresholdA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double thresholdB,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double scoreA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double scoreB) {

        // Valid thresholds: 0 <= askThreshold <= blockThreshold <= 1.
        double askThreshold = Math.min(thresholdA, thresholdB);
        double blockThreshold = Math.max(thresholdA, thresholdB);
        ThresholdConfiguration cfg = config(askThreshold, blockThreshold);

        // Totality + correct mapping: for every score in [0,1] the mapping yields exactly one action
        // matching the expected allow/ask/block band.
        assertMapping(scoreA, askThreshold, blockThreshold, cfg);
        assertMapping(scoreB, askThreshold, blockThreshold, cfg);

        // Monotonicity: for the lower and higher of the two scores, the higher score never maps to a
        // less restrictive action (using the ALLOW < ASK < BLOCK ordinal ordering).
        double lower = Math.min(scoreA, scoreB);
        double higher = Math.max(scoreA, scoreB);
        CorrectiveAction lowerAction = actionFor(lower, cfg);
        CorrectiveAction higherAction = actionFor(higher, cfg);
        assertThat(higherAction.ordinal())
                .as("higher score %s must not be less restrictive than lower score %s", higher, lower)
                .isGreaterThanOrEqualTo(lowerAction.ordinal());
    }

    /** Asserts the decision for a single score is exactly the expected allow/ask/block band. */
    private void assertMapping(double score, double askThreshold, double blockThreshold, ThresholdConfiguration cfg) {
        CorrectiveAction action = actionFor(score, cfg);

        CorrectiveAction expected;
        if (score < askThreshold) {
            expected = CorrectiveAction.ALLOW;
        } else if (score < blockThreshold) {
            expected = CorrectiveAction.ASK;
        } else {
            expected = CorrectiveAction.BLOCK;
        }

        // Exactly one action: the mapping is a total function producing a single, non-null action.
        assertThat(action).isNotNull();
        assertThat(action)
                .as("score %s with ask=%s block=%s", score, askThreshold, blockThreshold)
                .isEqualTo(expected);
    }

    /** Runs the isolated threshold map for a score and returns the resulting Corrective_Action. */
    private CorrectiveAction actionFor(double score, ThresholdConfiguration cfg) {
        Decision decision = engine.decide(nonTamperHumanEvent(), result(score), cfg, ProfileState.ACTIVE, true);
        return decision.action();
    }

    /** A benign, non-tamper HUMAN Command_Event so only the threshold map applies. */
    private static CommandEvent nonTamperHumanEvent() {
        return new CommandEvent(
                "evt-threshold",
                Actor.human("alice"),
                null,
                "ls -la",
                "/home/alice",
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    private static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config(double askThreshold, double blockThreshold) {
        return new ThresholdConfiguration(
                1,
                askThreshold,
                blockThreshold,
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15,
                200,
                5_000L,
                15_000L,
                1_200L,
                1_000L,
                "admin",
                1_000L);
    }
}
