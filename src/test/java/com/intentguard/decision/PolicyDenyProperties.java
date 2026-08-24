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
import com.intentguard.policy.PolicyAction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 2: A policy DENY always blocks and is never softened.
 *
 * <p>For any Divergence_Score in [0,1], any Threshold_Configuration, and any profile state
 * (including LEARNING), when the first matching PolicyRule has action DENY the resulting
 * Corrective_Action is BLOCK and is never downgraded to ASK by the learning clamp
 * (Validates: Requirements 1.2, 1.3, 2.7).
 *
 * <p>The DENY short-circuit is evaluated before the delegate (and therefore before the learning
 * clamp), so the property drives a full range of scores, thresholds, and both profile states —
 * including a benign command that would otherwise ALLOW and a LEARNING profile that would otherwise
 * clamp a block to ASK — and asserts the action is always BLOCK with reason {@code POLICY_DENY}.
 */
class PolicyDenyProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();

    @Property(tries = 200)
    void policyDenyAlwaysBlocks(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean humanSessionOpen) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        // A REQUIRE_CONFIRM-style ASK blast-radius floor is present to show DENY still wins as a
        // full BLOCK rather than being reduced by any lower floor.
        GuardrailContext gc =
                new GuardrailContext(
                        GuardrailTestSupport.policyDecision(PolicyAction.DENY),
                        new BlastRadiusResult(
                                CorrectiveAction.ASK, false, OptionalDouble.empty(), false, List.of()),
                        true,
                        DualControlStatus.NONE);

        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(), result, cfg, profileState, humanSessionOpen, gc);

        assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(decision.reasonCode()).isEqualTo(GuardrailDecisionEngine.REASON_POLICY_DENY);
        // Never softened: even a LEARNING profile does not downgrade a DENY to ASK.
        assertThat(decision.action()).isNotEqualTo(CorrectiveAction.ASK);
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }
}
