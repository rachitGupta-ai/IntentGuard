package com.intentguard.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;
import com.intentguard.policy.PolicyAction;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 9: A policy ALLOW suppresses a threshold-map block for
 * that event.
 *
 * <p>For any Divergence_Score, when the first matching PolicyRule has action ALLOW and no higher
 * short-circuit guardrail (tamper, DENY, block-on-access) applies, the threshold map does not
 * produce a BLOCK for that Command_Event (Validates: Requirements 2.9).
 *
 * <p>The property drives the full score range against an ACTIVE profile (so a block-range score
 * would otherwise map to BLOCK), applies an ALLOW policy with no other guardrail trigger, and
 * asserts the resulting action is never BLOCK — and is ALLOW precisely when the threshold map alone
 * would have blocked.
 */
class PolicyAllowSuppressionProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 200)
    void allowSuppressesThresholdMapBlock(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        CorrectiveAction base =
                delegate.decide(GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true)
                        .action();

        GuardrailContext gc =
                new GuardrailContext(
                        GuardrailTestSupport.policyDecision(PolicyAction.ALLOW),
                        BlastRadiusResult.none(),
                        true,
                        DualControlStatus.NONE);

        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true, gc);

        // The threshold map never produces a BLOCK for an ALLOW-policy event.
        assertThat(decision.action()).isNotEqualTo(CorrectiveAction.BLOCK);

        // When the threshold map alone would have blocked, the ALLOW policy suppresses it to ALLOW.
        if (base == CorrectiveAction.BLOCK) {
            assertThat(decision.action()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(decision.reasonCode()).isEqualTo(GuardrailDecisionEngine.REASON_POLICY_ALLOW);
        }
    }
}
