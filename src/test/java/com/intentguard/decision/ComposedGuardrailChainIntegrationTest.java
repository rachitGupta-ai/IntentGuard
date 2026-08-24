package com.intentguard.decision;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.Verdict;
import com.intentguard.dualcontrol.DualControlService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.policy.CommandPolicy;
import com.intentguard.policy.CommandPolicyService;
import com.intentguard.policy.PatternKind;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyRule;
import com.intentguard.policy.PolicyScope;

/**
 * End-to-end integration test for the composed guardrail chain wired into
 * {@link PipelineDecisionProvider} (Task 7.2). It drives the full ingest &rarr; scoring &rarr;
 * decision &rarr; explanation &rarr; persist path through a <em>real</em>
 * {@link GuardrailDecisionEngine} (wrapping {@code new DefaultDecisionEngine(new TamperClassifier())})
 * with a real {@link CommandPolicyService}, {@link BlastRadiusGuard}, {@link GuardrailConfigService},
 * and {@link DualControlService} wired via the provider's additive setters, and the LLM unavailable
 * so the deterministic guardrail-naming path is exercised.
 *
 * <p>Asserts (Req 2.7, 2.8, 2.11):
 * <ul>
 *   <li>a policy {@code DENY} rule blocks a command even when the Divergence_Score is in the
 *       allow range, and the persisted Audit_History record's {@code reasonCode} is
 *       {@code POLICY_DENY} with the matched rule id named in the Explanation;</li>
 *   <li>a {@code REQUIRE_CONFIRM} rule raises an allow-range decision to {@code ASK}, persisted
 *       with the matched rule id named in the Explanation.</li>
 * </ul>
 */
class ComposedGuardrailChainIntegrationTest {

    private static final String DENY_RULE_ID = "deny-deploy-prod";
    private static final String CONFIRM_RULE_ID = "confirm-restart-billing";

    /** A low, allow-range composite so only the policy guardrail can flag the command. */
    private static final double ALLOW_RANGE_COMPOSITE = 0.10;

    @Test
    void policyDenyBlocksLowScoringCommandAndNamesRuleInAuditAndExplanation() {
        CommandPolicy policy = policyWith(new PolicyRule(
                DENY_RULE_ID, PatternKind.GLOB, "deploy *", PolicyScope.any(), PolicyAction.DENY));

        GuardrailPipelineSupport.Harness harness = harness(policy);
        harness.withActiveProfile("alice");

        Verdict verdict = harness.decide(
                GuardrailPipelineSupport.signal("alice", "deploy prod database", InputOrigin.TYPED));

        // Blocked despite a low (allow-range) Divergence_Score: the DENY short-circuits (Req 2.7).
        assertThat(verdict.action()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(verdict.allowsExecution()).isFalse();
        assertThat(verdict.explanation()).contains(DENY_RULE_ID);

        AuditHistoryDocument record = harness.decisionRecord();
        assertThat(record.getCorrectiveAction()).isEqualTo("BLOCK");
        assertThat(record.getReasonCode()).isEqualTo(GuardrailReasonCodes.POLICY_DENY);
        assertThat(record.getDivergenceScore()).isEqualTo(ALLOW_RANGE_COMPOSITE);
        assertThat(record.getExplanation())
                .as("audit explanation must name the matched DENY rule id")
                .contains(DENY_RULE_ID);
    }

    @Test
    void policyRequireConfirmRaisesLowScoringCommandToAskAndNamesRule() {
        CommandPolicy policy = policyWith(new PolicyRule(
                CONFIRM_RULE_ID, PatternKind.GLOB, "restart *", PolicyScope.any(), PolicyAction.REQUIRE_CONFIRM));

        GuardrailPipelineSupport.Harness harness = harness(policy);
        harness.withActiveProfile("bob");

        Verdict verdict = harness.decide(
                GuardrailPipelineSupport.signal("bob", "restart billing service", InputOrigin.TYPED));

        // The allow-range decision is raised to ASK by the REQUIRE_CONFIRM rule (Req 2.8).
        assertThat(verdict.action()).isEqualTo(CorrectiveAction.ASK);
        assertThat(verdict.explanation()).contains(CONFIRM_RULE_ID);

        AuditHistoryDocument record = harness.decisionRecord();
        assertThat(record.getCorrectiveAction()).isEqualTo("ASK");
        assertThat(record.getReasonCode()).isEqualTo(GuardrailReasonCodes.POLICY_REQUIRE_CONFIRM);
        assertThat(record.getExplanation())
                .as("audit explanation must name the matched REQUIRE_CONFIRM rule id")
                .contains(CONFIRM_RULE_ID);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static CommandPolicy policyWith(PolicyRule rule) {
        return new CommandPolicy(1, List.of(rule), "admin", 1_000L);
    }

    /**
     * A harness with the whole guardrail chain wired: the CommandPolicy under test, a benign
     * blast-radius config (no protected targets or destructive verbs so it never interferes with
     * these policy-only assertions), and a dual-control service. Thresholds are ask=0.4/block=0.7
     * and the scored composite is in the allow range, so only the policy can flag the command.
     */
    private static GuardrailPipelineSupport.Harness harness(CommandPolicy policy) {
        ThresholdConfiguration thresholds = GuardrailPipelineSupport.thresholds(0.4, 0.7, 1);
        DivergenceResult scored = GuardrailPipelineSupport.scoredResult(ALLOW_RANGE_COMPOSITE);
        GuardrailConfig guardrailConfig = GuardrailConfig.defaults("admin", 0L);

        CommandPolicyService commandPolicyService = GuardrailPipelineSupport.commandPolicyService(policy);
        BlastRadiusGuard blastRadiusGuard = new BlastRadiusGuard();
        GuardrailConfigService guardrailConfigService =
                GuardrailPipelineSupport.guardrailConfigService(guardrailConfig);

        GuardrailPipelineSupport.Harness harness = GuardrailPipelineSupport.harness(
                scored, thresholds, commandPolicyService, blastRadiusGuard, guardrailConfigService, null);
        DualControlService dualControlService =
                GuardrailPipelineSupport.dualControlService(guardrailConfig, harness.auditRepository);
        harness.provider.setDualControlService(dualControlService);
        return harness;
    }
}
