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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 13: Protected-target access raises the floor to ASK, or
 * blocks on block-on-access.
 *
 * <p>For any Command_Event that reads from, writes to, or otherwise accesses a configured
 * ProtectedTarget (path, host, or resource), the blast-radius result raises the Corrective_Action
 * floor to at least ASK; and when that ProtectedTarget is configured block-on-access, the
 * Corrective_Action is BLOCK regardless of the Divergence_Score (Validates: Requirements 3.2, 3.3,
 * 3.4).
 *
 * <p>Exercised at the {@link GuardrailDecisionEngine} level with a hand-made
 * {@link BlastRadiusResult}: a protected-access result (floor = ASK) must yield {@code max(base,
 * ASK)} — at least ASK; a block-on-access result must yield BLOCK even for a low, allow-range score.
 */
class ProtectedTargetFloorProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 200)
    void protectedTargetAccessRaisesFloorOrBlocksOnAccess(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean blockOnAccess) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        if (blockOnAccess) {
            // A block-on-access ProtectedTarget short-circuits to BLOCK regardless of the score
            // (here even a 0.0 allow-range score is forced to BLOCK) (Req 3.3).
            BlastRadiusResult blast =
                    new BlastRadiusResult(
                            CorrectiveAction.ALLOW,
                            true,
                            OptionalDouble.empty(),
                            false,
                            List.of("prod-db"));
            Decision decision =
                    engine.decide(
                            GuardrailTestSupport.benignEvent(),
                            GuardrailTestSupport.result(0.0),
                            cfg,
                            profileState,
                            true,
                            context(blast));

            assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
        } else {
            // A protected path/host/resource read or write raises the action floor to at least ASK
            // (Req 3.2, 3.4).
            BlastRadiusResult blast =
                    new BlastRadiusResult(
                            CorrectiveAction.ASK,
                            false,
                            OptionalDouble.empty(),
                            false,
                            List.of("ssh-keys"));
            CorrectiveAction base =
                    delegate.decide(GuardrailTestSupport.benignEvent(), result, cfg, profileState, true)
                            .action();
            Decision decision =
                    engine.decide(
                            GuardrailTestSupport.benignEvent(),
                            result,
                            cfg,
                            profileState,
                            true,
                            context(blast));

            assertThat(decision.action()).isEqualTo(CorrectiveAction.max(base, CorrectiveAction.ASK));
            assertThat(decision.action().ordinal())
                    .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        }
    }

    private static GuardrailContext context(BlastRadiusResult blast) {
        return new GuardrailContext(
                com.intentguard.policy.PolicyDecision.none(), blast, true, DualControlStatus.NONE);
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }
}
