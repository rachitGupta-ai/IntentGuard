package com.intentguard.decision;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 15: A destructive verb raises the Divergence_Score to
 * at least the destructive floor.
 *
 * <p>For any Command_Event matching a configured destructive-verb pattern and any original
 * Divergence_Score, the effective score fed to the threshold map is at least the configured
 * destructive-operation floor (Validates: Requirements 3.6).
 *
 * <p>Exercised at the {@link GuardrailDecisionEngine} level with a hand-made
 * {@link BlastRadiusResult} carrying {@code scoreFloor = OptionalDouble.of(floor)}: with a low base
 * score and thresholds whose block band sits below the floor, the delegate would ALLOW the original
 * score, but the raised effective score must push the decision into the block band. The property
 * asserts both that the score the decision reports is at least the floor and that the resulting
 * action reflects the raised score.
 */
class DestructiveVerbScoreFloorProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 200)
    void destructiveVerbRaisesEffectiveScoreToFloor(
            @ForAll @DoubleRange(min = 0.0, max = 0.4) double baseScore,
            @ForAll @DoubleRange(min = 0.5, max = 1.0) double floor) {

        // Threshold bands both sit strictly below the destructive floor and above the base score,
        // so the original score is in the ALLOW band while the floored score is in the BLOCK band.
        ThresholdConfiguration cfg = GuardrailTestSupport.config(0.45, 0.49);
        DivergenceResult result = GuardrailTestSupport.result(baseScore);

        // Sanity: without the destructive floor the delegate allows this low-score benign event
        // (ACTIVE profile, open human session so no learning clamp / containment interferes).
        Decision baseDecision =
                delegate.decide(GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true);
        assertThat(baseDecision.action()).isEqualTo(CorrectiveAction.ALLOW);

        BlastRadiusResult blast =
                new BlastRadiusResult(
                        CorrectiveAction.ALLOW,
                        false,
                        OptionalDouble.of(floor),
                        false,
                        List.of("destructive-verb"));

        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(),
                        result,
                        cfg,
                        ProfileState.ACTIVE,
                        true,
                        new GuardrailContext(
                                com.intentguard.policy.PolicyDecision.none(),
                                blast,
                                true,
                                DualControlStatus.NONE));

        // The effective score fed to the threshold map is at least the configured floor (Req 3.6).
        assertThat(decision.score()).isGreaterThanOrEqualTo(floor);
        // And the raised score reflects in the action: it now falls in the block band.
        assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
    }
}
