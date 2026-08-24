package com.intentguard.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
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
 * Feature: intentguard-guardrails, Property 5: With no guardrail trigger the threshold-map action
 * passes through.
 *
 * <p>For any Command_Event that is not a tamper attempt, matches no PolicyRule, accesses no
 * ProtectedTarget, has determinate blast radius at or below the limit, and raises no floor above
 * the threshold-map result, the Corrective_Action equals the action produced by the existing
 * threshold-map / learning-clamp / agent-containment delegate for that score and state
 * (Validates: Requirements 1.8).
 *
 * <p>With an {@linkplain GuardrailContext#empty() empty} guardrail context, the wrapping engine
 * must reduce exactly to the delegate {@link DefaultDecisionEngine} — the property asserts the full
 * Decision (action, score, and reason code) is identical across arbitrary scores, thresholds,
 * profile states, actor types, and session flags.
 */
class ThresholdPassthroughProperties {

    private final GuardrailDecisionEngine engine = GuardrailTestSupport.engine();
    private final DefaultDecisionEngine delegate = GuardrailTestSupport.delegate();

    @Property(tries = 300)
    void emptyContextPassesThroughDelegateDecision(
            @ForAll("nonTamperEvents") CommandEvent event,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB,
            @ForAll("profileStates") ProfileState profileState,
            @ForAll boolean humanSessionOpen) {

        ThresholdConfiguration cfg =
                GuardrailTestSupport.config(Math.min(boundA, boundB), Math.max(boundA, boundB));
        DivergenceResult result = GuardrailTestSupport.result(score);

        Decision expected = delegate.decide(event, result, cfg, profileState, humanSessionOpen);
        Decision actual =
                engine.decide(event, result, cfg, profileState, humanSessionOpen, GuardrailContext.empty());

        assertThat(actual).isEqualTo(expected);
    }

    @Provide
    Arbitrary<CommandEvent> nonTamperEvents() {
        return Arbitraries.of(
                GuardrailTestSupport.event(Actor.human("alice"), "ls -la", "/home/alice"),
                GuardrailTestSupport.event(Actor.agent("bot", "alice"), "npm run build", "/repo"),
                GuardrailTestSupport.event(Actor.human("bob"), "git status", "/repo/app"));
    }

    @Provide
    Arbitrary<ProfileState> profileStates() {
        return Arbitraries.of(ProfileState.LEARNING, ProfileState.ACTIVE);
    }
}
