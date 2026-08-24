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
 * Feature: intentguard-guardrails, Property 14: A blast radius over the mass-operation limit raises
 * the floor to ASK.
 *
 * <p>For any Command_Event whose estimated BlastRadius affected-count exceeds the configured
 * mass-operation limit, the Corrective_Action floor is raised to at least ASK; a count at or below
 * the limit does not raise the floor on that basis alone (Validates: Requirements 3.5).
 *
 * <p>Exercised at the {@link GuardrailDecisionEngine} level with a hand-made
 * {@link BlastRadiusResult}: an over-the-limit breach is modelled as {@code floor = ASK} (the
 * BlastRadiusGuard's contract for a mass-op breach) and must yield {@code max(base, ASK)}; an
 * at-or-below-limit result is modelled as {@code floor = ALLOW} and must leave the delegate decision
 * untouched.
 */
class MassOperationFloorProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 200)
    void massOperationOverLimitRaisesFloorToAsk(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean overLimit) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        Decision baseDecision =
                delegate.decide(GuardrailTestSupport.benignEvent(), result, cfg, profileState, true);
        CorrectiveAction base = baseDecision.action();

        BlastRadiusResult blast =
                overLimit
                        ? new BlastRadiusResult(
                                CorrectiveAction.ASK,
                                false,
                                OptionalDouble.empty(),
                                false,
                                List.of("mass-op-limit"))
                        : BlastRadiusResult.none();

        Decision decision =
                engine.decide(
                        GuardrailTestSupport.benignEvent(),
                        result,
                        cfg,
                        profileState,
                        true,
                        new GuardrailContext(
                                com.intentguard.policy.PolicyDecision.none(),
                                blast,
                                true,
                                DualControlStatus.NONE));

        if (overLimit) {
            // Over the mass-operation limit raises the floor to at least ASK (Req 3.5).
            assertThat(decision.action()).isEqualTo(CorrectiveAction.max(base, CorrectiveAction.ASK));
            assertThat(decision.action().ordinal())
                    .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        } else {
            // At or below the limit: no raise on that basis alone — passthrough of the delegate.
            assertThat(decision).isEqualTo(baseDecision);
        }
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }
}
