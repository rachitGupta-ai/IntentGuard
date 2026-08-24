package com.intentguard.dualcontrol;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.decision.DefaultDecisionEngine;
import com.intentguard.decision.DualControlStatus;
import com.intentguard.decision.GuardrailContext;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;
import com.intentguard.policy.PolicyDecision;
import com.intentguard.scoring.CommandNormalizer;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 21: Agent actions outside their capability scope raise
 * the floor to ASK.
 *
 * <p>For any AGENT Command_Event whose command class falls outside the Agent_Actor's configured
 * capability scope, the Corrective_Action floor is raised to at least ASK (Validates: Requirements
 * 4.8). Exercised via the {@link com.intentguard.dualcontrol.CapabilityScope} helper and the
 * {@link GuardrailDecisionEngine} floor.
 */
class CapabilityScopeFloorProperties {

    private static final String AGENT_ID = "agent-ci-bot";

    private static final List<String> ALL_CATEGORIES =
            List.of("vcs", "network", "orchestration", "package", "build", "filesystem", "privilege");

    // A representative command per category, so the generated command's class is deterministic.
    private static final Map<String, String> COMMAND_BY_CATEGORY = Map.of(
            "vcs", "git status",
            "network", "curl http://x",
            "orchestration", "kubectl delete ns x",
            "package", "npm install",
            "build", "mvn package",
            "filesystem", "ls -la",
            "privilege", "sudo rm");

    private final GuardrailDecisionEngine engine = new GuardrailDecisionEngine(
            new DefaultDecisionEngine(new TamperClassifier()), new TamperClassifier());

    @Property(tries = 200)
    void outOfScopeAgentRaisesFloorToAskAndInScopeDoesNot(
            @ForAll("categories") String commandCategory, @ForAll boolean inScope) {

        String commandText = COMMAND_BY_CATEGORY.get(commandCategory);
        // Sanity: the representative command really has the expected class.
        assertThat(CommandNormalizer.category(commandText)).isEqualTo(commandCategory);

        // Build the agent's configured scope: include the command's class (in scope) or a single
        // distinct class that excludes it (out of scope).
        List<String> scope = inScope ? List.of(commandCategory) : List.of(otherCategory(commandCategory));
        Map<String, List<String>> capabilityScopes = Map.of(AGENT_ID, scope);

        CommandEvent event = DualControlTestSupport.agentEvent("evt", AGENT_ID, "alice", commandText, 1_000L);

        // Helper reflects the scope decision (Req 4.8).
        boolean within = CapabilityScope.isWithinScope(event, capabilityScopes);
        assertThat(within).isEqualTo(inScope);

        // The engine raises the floor to at least ASK for an out-of-scope agent, from a benign base.
        GuardrailContext gc = new GuardrailContext(
                PolicyDecision.none(), BlastRadiusResult.none(), within, DualControlStatus.NONE);
        Decision decision =
                engine.decide(event, result(0.0), config(0.4, 0.7), ProfileState.ACTIVE, true, gc);

        if (inScope) {
            assertThat(decision.action()).isEqualTo(CorrectiveAction.ALLOW);
        } else {
            assertThat(decision.action().ordinal())
                    .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        }
    }

    private static String otherCategory(String category) {
        for (String candidate : ALL_CATEGORIES) {
            if (!candidate.equals(category)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no other category");
    }

    @Provide
    Arbitrary<String> categories() {
        return Arbitraries.of(COMMAND_BY_CATEGORY.keySet());
    }

    private static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config(double ask, double block) {
        return new ThresholdConfiguration(
                1,
                ask,
                block,
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
