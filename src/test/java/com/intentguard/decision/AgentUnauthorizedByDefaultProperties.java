package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 18: Agent actions are unauthorized by default.
 *
 * <p>For any Command_Event from an Agent_Actor for whom no human principal Intent_Session is open,
 * the applied Corrective_Action is at least {@code ask} (never {@code allow}), subject to the
 * Threshold_Configuration (Validates: Requirements 13.4).
 *
 * <p>This exercises the agent-containment rule of {@link DefaultDecisionEngine}: an agent with no
 * open human session is unauthorized-by-default, so a score that would otherwise map to
 * {@code allow} is raised to {@code ask}. A non-tamper command is used so the tamper override never
 * fires; that override would independently force a {@code block}, which is still "at least ask" but
 * would not test the containment path. Across an arbitrary Divergence_Score, valid thresholds, and
 * any {@link ProfileState}, the resulting action is never {@code allow}.
 */
class AgentUnauthorizedByDefaultProperties {

    private final DefaultDecisionEngine engine = new DefaultDecisionEngine(new TamperClassifier());

    @Property(tries = 200)
    void agentWithNoOpenHumanSessionIsNeverAllowed(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll("thresholds") double[] thresholds,
            @ForAll ProfileState profileState,
            @ForAll("benignCommands") String commandText) {

        double askThreshold = thresholds[0];
        double blockThreshold = thresholds[1];

        CommandEvent agentEvent = agentEvent(commandText);
        ThresholdConfiguration cfg = config(askThreshold, blockThreshold);

        Decision decision = engine.decide(agentEvent, result(score), cfg, profileState, false);

        // ALLOW < ASK < BLOCK. The applied action must be at least ASK, i.e. never ALLOW.
        assertThat(decision.action())
                .as("agent with no open human session, score=%s, ask=%s, block=%s, profile=%s",
                        score, askThreshold, blockThreshold, profileState)
                .isNotEqualTo(CorrectiveAction.ALLOW);
        assertThat(decision.action().ordinal())
                .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
    }

    /** Two ordered thresholds in [0,1]: {@code [askThreshold, blockThreshold]} with ask <= block. */
    @Provide
    Arbitrary<double[]> thresholds() {
        Arbitrary<Double> a = Arbitraries.doubles().between(0.0, 1.0);
        Arbitrary<Double> b = Arbitraries.doubles().between(0.0, 1.0);
        return Combinators.combine(a, b)
                .as((x, y) -> new double[] {Math.min(x, y), Math.max(x, y)});
    }

    /** Non-tamper command texts, so the tamper override never fires and containment is exercised. */
    @Provide
    Arbitrary<String> benignCommands() {
        return Arbitraries.of(
                "ls -la",
                "cat README.md",
                "git status",
                "echo hello",
                "pwd",
                "grep -r foo src",
                "python app.py",
                "npm test");
    }

    private static CommandEvent agentEvent(String commandText) {
        return new CommandEvent(
                "evt-1",
                Actor.agent("agent-1", "alice"),
                null,
                commandText,
                "/home/alice",
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    private static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config(double askThreshold, double blockThreshold) {
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
}
