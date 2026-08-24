package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.ActorType;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.SignalSource;
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
 * Feature: intentguard-guardrails, Property 3: Tamper attempts always block and are never softened.
 *
 * <p>For any Command_Event classified as a tamper attempt, any Divergence_Score, any
 * GuardrailContext, and any profile state (including LEARNING), the Corrective_Action is BLOCK with
 * reason REJECTED_TAMPER, ahead of every other guardrail (Validates: Requirements 1.5, 1.7).
 *
 * <p>The generator embeds a known tamper fragment in the command text or cwd and pairs it with an
 * arbitrary GuardrailContext — including a policy ALLOW and no blast-radius trigger, which would
 * otherwise permit the event — to prove the tamper override precedes and overrides every later
 * guardrail regardless of score or profile state.
 */
class TamperPrecedenceProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();

    @Property(tries = 200)
    void tamperAlwaysBlocksAheadOfEveryGuardrail(
            @ForAll("tamperEvents") CommandEvent event,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean humanSessionOpen,
            @ForAll("contexts") GuardrailContext guardrail) {

        // Sanity: the generated event really is a tamper attempt.
        assertThat(new TamperClassifier().isTamperAttempt(event)).isTrue();

        ThresholdConfiguration cfg = GuardrailTestSupport.config(0.4, 0.7);
        DivergenceResult result = GuardrailTestSupport.result(score);

        Decision decision = engine.decide(event, result, cfg, profileState, humanSessionOpen, guardrail);

        assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(decision.score()).isEqualTo(1.0);
        assertThat(decision.reasonCode()).isEqualTo(GuardrailDecisionEngine.REASON_TAMPER);
    }

    @Provide
    Arbitrary<CommandEvent> tamperEvents() {
        Arbitrary<String> fragments =
                Arbitraries.of("intentguard", "/etc/intentguard", "threshold_config", "audit_history");
        Arbitrary<Boolean> inCommand = Arbitraries.of(true, false);
        Arbitrary<ActorType> actorTypes = Arbitraries.of(ActorType.HUMAN, ActorType.AGENT);
        return Combinators.combine(fragments, inCommand, actorTypes)
                .as((fragment, placeInCommand, actorType) -> {
                    String commandText = placeInCommand ? "cat " + fragment : "ls -la";
                    String cwd = placeInCommand ? "/home/mallory" : "/var/lib/" + fragment;
                    Actor actor =
                            actorType == ActorType.AGENT
                                    ? Actor.agent("mallory", "alice")
                                    : Actor.human("mallory");
                    return new CommandEvent(
                            "evt-tamper",
                            actor,
                            null,
                            commandText,
                            cwd,
                            null,
                            Map.of(),
                            1_000L,
                            InputOrigin.TYPED,
                            SignalSource.HOOK,
                            IntentSource.NONE,
                            AgentRiskMarkers.none());
                });
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
                        GuardrailTestSupport.policyDecision(PolicyAction.ALLOW),
                        GuardrailTestSupport.policyDecision(PolicyAction.DENY));
        Arbitrary<BlastRadiusResult> blastRadii =
                Arbitraries.of(
                        BlastRadiusResult.none(),
                        new BlastRadiusResult(
                                CorrectiveAction.ALLOW, true, OptionalDouble.empty(), false, List.of("boa")));
        Arbitrary<DualControlStatus> dualControl = Arbitraries.of(DualControlStatus.values());
        return Combinators.combine(policies, blastRadii, dualControl)
                .as((p, br, dc) -> new GuardrailContext(p, br, true, dc));
    }
}
