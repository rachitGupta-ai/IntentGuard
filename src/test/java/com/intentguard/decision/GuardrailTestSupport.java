package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.policy.PatternKind;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyDecision;
import com.intentguard.policy.PolicyRule;
import com.intentguard.policy.PolicyScope;

/**
 * Shared, dependency-free builders for the {@link GuardrailDecisionEngine} property and unit tests:
 * a benign non-tamper {@link CommandEvent}, a {@link DivergenceResult} carrying a composite score, a
 * valid {@link ThresholdConfiguration} with given thresholds, and hand-made {@link PolicyDecision}s.
 * The engine under test is always constructed exactly as the design specifies:
 * {@code new GuardrailDecisionEngine(new DefaultDecisionEngine(new TamperClassifier()), new TamperClassifier())}.
 */
final class GuardrailTestSupport {

    private GuardrailTestSupport() {}

    static GuardrailDecisionEngine engine() {
        return new GuardrailDecisionEngine(
                new DefaultDecisionEngine(new TamperClassifier()), new TamperClassifier());
    }

    /** The unwrapped delegate, for asserting passthrough equals the existing engine's decision. */
    static DefaultDecisionEngine delegate() {
        return new DefaultDecisionEngine(new TamperClassifier());
    }

    /** A benign, non-tamper HUMAN command so neither the tamper override nor containment fires. */
    static CommandEvent benignEvent() {
        return event(Actor.human("alice"), "ls -la", "/home/alice");
    }

    static CommandEvent event(Actor actor, String commandText, String cwd) {
        return new CommandEvent(
                "evt-1",
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
    }

    static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    static ThresholdConfiguration config(double askThreshold, double blockThreshold) {
        return new ThresholdConfiguration(
                1,
                askThreshold,
                blockThreshold,
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15,
                200,
                5_000L,
                15_000L,
                1_200L,
                1_000L,
                "admin",
                1_000L);
    }

    /** A PolicyDecision whose first-matching rule carries the given action. */
    static PolicyDecision policyDecision(PolicyAction action) {
        PolicyRule rule =
                new PolicyRule("rule-" + action, PatternKind.GLOB, "*", PolicyScope.any(), action);
        return PolicyDecision.of(rule);
    }
}
