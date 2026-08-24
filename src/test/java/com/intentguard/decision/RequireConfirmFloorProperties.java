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
 * Feature: intentguard-guardrails, Property 8: REQUIRE_CONFIRM raises the floor to ASK without
 * lowering a block.
 *
 * <p>For any base Corrective_Action, when the first matching PolicyRule has action REQUIRE_CONFIRM
 * the resulting action equals max(base, ASK) — at least ASK, and still BLOCK when the threshold map
 * already yields BLOCK (Validates: Requirements 2.8).
 *
 * <p>The base action is produced by the threshold map over an arbitrary score against an ACTIVE
 * profile (so a block-range score genuinely yields BLOCK); the REQUIRE_CONFIRM policy is applied via
 * the context and the result is asserted to equal {@code max(base, ASK)}.
 */
class RequireConfirmFloorProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 200)
    void requireConfirmRaisesToAskAndKeepsBlock(
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
                        GuardrailTestSupport.policyDecision(PolicyAction.REQUIRE_CONFIRM),
                        BlastRadiusResult.none(),
                        true,
                        DualControlStatus.NONE);

        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true, gc);

        assertThat(decision.action()).isEqualTo(CorrectiveAction.max(base, CorrectiveAction.ASK));
        // At least ASK in every case.
        assertThat(decision.action().ordinal())
                .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        // A threshold-map BLOCK is retained, not lowered to ASK.
        if (base == CorrectiveAction.BLOCK) {
            assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
        }
    }
}
