package com.intentguard.decision;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyDecision;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 1: Guardrail precedence is total and deterministic.
 *
 * <p>For any Command_Event and any GuardrailContext, threshold score, and profile state,
 * {@link GuardrailDecisionEngine#decide} yields exactly one Decision that equals a reference oracle
 * applying the stages strictly in order (tamper override &rarr; policy DENY &rarr;
 * blast-radius/protected-target &rarr; threshold map &rarr; learning clamp &rarr; agent containment
 * &rarr; dual-control), and repeated computations with identical inputs produce an identical
 * Decision (Validates: Requirements 1.1, 2.3).
 */
class GuardrailPrecedenceProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final TamperClassifier tamperClassifier = new TamperClassifier();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 300)
    void precedenceIsTotalAndDeterministic(
            @ForAll("events") CommandEvent event,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double composite,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean humanSessionOpen,
            @ForAll("contexts") GuardrailContext guardrail) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(composite);

        Decision actual = engine.decide(event, result, cfg, profileState, humanSessionOpen, guardrail);

        // Totality: a single Decision is produced (no exception, exactly one action/score/reason).
        assertThat(actual).isNotNull();

        // Equality with an independently coded reference oracle applying the stages in order.
        Decision oracle = oracle(event, result, cfg, profileState, humanSessionOpen, guardrail);
        assertThat(actual.action()).isEqualTo(oracle.action());
        assertThat(actual.score()).isEqualTo(oracle.score());

        // Determinism: identical inputs yield an identical Decision on every evaluation.
        Decision again = engine.decide(event, result, cfg, profileState, humanSessionOpen, guardrail);
        assertThat(again).isEqualTo(actual);
    }

    /** Independent reference oracle: applies the ordered stages using {@code max} over floors. */
    private Decision oracle(
            CommandEvent event,
            DivergenceResult result,
            ThresholdConfiguration cfg,
            ProfileState profileState,
            boolean humanSessionOpen,
            GuardrailContext gc) {

        if (tamperClassifier.isTamperAttempt(event)) {
            return new Decision(CorrectiveAction.BLOCK, 1.0, "REJECTED_TAMPER");
        }
        if (gc.policy().isDeny()) {
            return new Decision(CorrectiveAction.BLOCK, result.composite(), "POLICY_DENY");
        }
        if (gc.blastRadius().blockOnAccessHit()) {
            return new Decision(CorrectiveAction.BLOCK, result.composite(), "BLAST_RADIUS");
        }

        double effective = result.composite();
        OptionalDouble scoreFloor = gc.blastRadius().scoreFloor();
        if (scoreFloor.isPresent()) {
            effective = Math.max(effective, scoreFloor.getAsDouble());
        }
        Decision base =
                delegate.decide(event, GuardrailTestSupport.result(effective), cfg, profileState, humanSessionOpen);

        CorrectiveAction action = base.action();
        if (gc.policy().isAllow() && action == CorrectiveAction.BLOCK) {
            action = CorrectiveAction.ALLOW;
        }
        if (gc.policy().isRequireConfirm()) {
            action = CorrectiveAction.max(action, CorrectiveAction.ASK);
        }
        action = CorrectiveAction.max(action, gc.blastRadius().floor());
        if (!gc.withinCapabilityScope()) {
            action = CorrectiveAction.max(action, CorrectiveAction.ASK);
        }
        // Dual-control stage: a PENDING confirmation withholds the event (at least ASK), a
        // TIMED_OUT confirmation resolves to BLOCK (Req 4.1, 4.2, 4.5).
        if (gc.dualControl() == DualControlStatus.PENDING) {
            action = CorrectiveAction.max(action, CorrectiveAction.ASK);
        }
        if (gc.dualControl() == DualControlStatus.TIMED_OUT) {
            action = CorrectiveAction.max(action, CorrectiveAction.BLOCK);
        }
        return new Decision(action, base.score(), "ORACLE");
    }

    @Provide
    Arbitrary<CommandEvent> events() {
        // A mix of benign and tamper events so the tamper short-circuit is exercised too.
        Arbitrary<CommandEvent> benign =
                Arbitraries.of(
                        GuardrailTestSupport.event(Actor.human("alice"), "ls -la", "/home/alice"),
                        GuardrailTestSupport.event(Actor.agent("bot", "alice"), "npm test", "/repo"));
        Arbitrary<CommandEvent> tamper =
                Arbitraries.just(
                        GuardrailTestSupport.event(
                                Actor.human("mallory"), "cat /etc/intentguard/thresholds.yml", "/tmp"));
        return Arbitraries.oneOf(benign, tamper);
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }

    @Provide
    Arbitrary<GuardrailContext> contexts() {
        Arbitrary<PolicyDecision> policies =
                Arbitraries.of(
                        PolicyDecision.none(),
                        GuardrailTestSupport.policyDecision(PolicyAction.DENY),
                        GuardrailTestSupport.policyDecision(PolicyAction.REQUIRE_CONFIRM),
                        GuardrailTestSupport.policyDecision(PolicyAction.ALLOW));
        Arbitrary<BlastRadiusResult> blastRadii =
                Arbitraries.of(
                        BlastRadiusResult.none(),
                        new BlastRadiusResult(
                                CorrectiveAction.ASK, false, OptionalDouble.empty(), false, List.of("pt-1")),
                        new BlastRadiusResult(
                                CorrectiveAction.ASK, false, OptionalDouble.empty(), true, List.of("indet")),
                        new BlastRadiusResult(
                                CorrectiveAction.ALLOW, false, OptionalDouble.of(0.90), false, List.of("verb")),
                        new BlastRadiusResult(
                                CorrectiveAction.ALLOW, true, OptionalDouble.empty(), false, List.of("boa")));
        Arbitrary<Boolean> withinScope = Arbitraries.of(true, false);
        Arbitrary<DualControlStatus> dualControl =
                Arbitraries.of(DualControlStatus.values());

        return Combinators.combine(policies, blastRadii, withinScope, dualControl)
                .as(GuardrailContext::new);
    }
}
