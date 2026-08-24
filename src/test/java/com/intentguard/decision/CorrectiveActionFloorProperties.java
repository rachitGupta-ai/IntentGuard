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
import com.intentguard.policy.PolicyDecision;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 4: The Corrective_Action floor is monotone.
 *
 * <p>For any base Corrective_Action and any multiset of Corrective_Action floors raised by
 * guardrails, the final action equals the most restrictive of the base and all floors
 * ({@link CorrectiveAction#max}), and adding any further floor never yields a less restrictive
 * action, using the ordering ALLOW &lt; ASK &lt; BLOCK (Validates: Requirements 1.4).
 *
 * <p>The base action is produced by the threshold map over an arbitrary score; the floors are
 * raised via the guardrail context (REQUIRE_CONFIRM &rarr; ASK, blast-radius floor, out-of-scope
 * &rarr; ASK, dual-control timeout &rarr; BLOCK). The test asserts the engine's action equals the
 * independent {@code max} of the base and every raised floor, and that toggling a further floor on
 * never lowers the resulting action.
 */
class CorrectiveActionFloorProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 300)
    void floorIsMostRestrictiveAndMonotone(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll boolean requireConfirm,
            @ForAll("floors") CorrectiveAction blastFloor,
            @ForAll boolean withinScope,
            @ForAll boolean dualTimeout) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        // Base action from the pure threshold map (ACTIVE profile, human session open, benign event).
        CorrectiveAction base =
                delegate.decide(GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true)
                        .action();

        // Expected = max of the base and every raised floor.
        CorrectiveAction expected = base;
        if (requireConfirm) {
            expected = CorrectiveAction.max(expected, CorrectiveAction.ASK);
        }
        expected = CorrectiveAction.max(expected, blastFloor);
        if (!withinScope) {
            expected = CorrectiveAction.max(expected, CorrectiveAction.ASK);
        }
        if (dualTimeout) {
            expected = CorrectiveAction.max(expected, CorrectiveAction.BLOCK);
        }

        GuardrailContext gc = context(requireConfirm, blastFloor, withinScope, dualTimeout);
        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(), result, cfg, ProfileState.ACTIVE, true, gc);

        assertThat(decision.action()).isEqualTo(expected);

        // Monotonicity: turning any further floor on never lowers restrictiveness.
        CorrectiveAction withTimeout =
                engine.decide(
                                GuardrailTestSupport.benignEvent(),
                                result,
                                cfg,
                                ProfileState.ACTIVE,
                                true,
                                context(requireConfirm, blastFloor, withinScope, true))
                        .action();
        assertThat(withTimeout.ordinal()).isGreaterThanOrEqualTo(decision.action().ordinal());

        CorrectiveAction outOfScope =
                engine.decide(
                                GuardrailTestSupport.benignEvent(),
                                result,
                                cfg,
                                ProfileState.ACTIVE,
                                true,
                                context(requireConfirm, blastFloor, false, dualTimeout))
                        .action();
        assertThat(outOfScope.ordinal()).isGreaterThanOrEqualTo(decision.action().ordinal());
    }

    private static GuardrailContext context(
            boolean requireConfirm, CorrectiveAction blastFloor, boolean withinScope, boolean dualTimeout) {
        PolicyDecision policy =
                requireConfirm
                        ? GuardrailTestSupport.policyDecision(PolicyAction.REQUIRE_CONFIRM)
                        : PolicyDecision.none();
        BlastRadiusResult blastRadius =
                new BlastRadiusResult(blastFloor, false, OptionalDouble.empty(), false, List.of());
        DualControlStatus dc = dualTimeout ? DualControlStatus.TIMED_OUT : DualControlStatus.NONE;
        return new GuardrailContext(policy, blastRadius, withinScope, dc);
    }

    @Provide
    Arbitrary<CorrectiveAction> floors() {
        // Blast-radius floors are only ever ALLOW (no floor) or ASK; a BLOCK is a short-circuit.
        return Arbitraries.of(CorrectiveAction.ALLOW, CorrectiveAction.ASK);
    }
}
