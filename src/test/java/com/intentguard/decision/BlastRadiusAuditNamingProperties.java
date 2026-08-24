package com.intentguard.decision;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.blastradius.ProtectedTarget;
import com.intentguard.blastradius.TargetKind;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.Verdict;
import com.intentguard.persistence.AuditHistoryDocument;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 16: Blast-radius triggers are audited and named in the
 * explanation.
 *
 * <p>For any blast-radius / protected-target guardrail that changes the Corrective_Action or raises
 * the Divergence_Score of an event, the persisted Audit_History record names the triggering
 * guardrail id and the Explanation contains it (<strong>Validates: Requirements 3.7</strong>).
 *
 * <p>The property drives the fully-wired {@link PipelineDecisionProvider} end-to-end through a real
 * {@link GuardrailDecisionEngine}, a real {@link BlastRadiusGuard}, and a {@link GuardrailConfigService}
 * serving a generated {@link GuardrailConfig}, with the LLM unavailable so the deterministic
 * guardrail-naming path must surface the id. Each iteration exercises one of three triggering
 * guardrails whose {@code BlastRadiusResult} carries a triggered guardrail id — a protected target
 * (floor &rarr; {@code ASK}), a block-on-access protected target (short-circuit {@code BLOCK}), and
 * a destructive verb (Divergence_Score floor &rarr; threshold {@code BLOCK}) — over an allow-range
 * base composite so the guardrail is the sole cause of the flag. It then asserts the decision is at
 * least {@code ASK} and that both the returned Explanation and the persisted Audit_History record
 * name the triggering guardrail id.
 */
class BlastRadiusAuditNamingProperties {

    private enum Trigger {
        /** A protected-target access raising the floor to ASK (Req 3.2, 3.4). */
        PROTECTED_TARGET,
        /** A block-on-access protected target short-circuiting to BLOCK (Req 3.3). */
        BLOCK_ON_ACCESS,
        /** A destructive verb raising the Divergence_Score floor (Req 3.6). */
        DESTRUCTIVE_VERB
    }

    @Property(tries = 200)
    void blastRadiusTriggersAreAuditedAndNamed(@ForAll("cases") Case testCase) {
        GuardrailConfig guardrailConfig = testCase.config();
        ThresholdConfiguration thresholds = GuardrailPipelineSupport.thresholds(0.4, 0.7, 1);
        DivergenceResult scored = GuardrailPipelineSupport.scoredResult(testCase.baseComposite());

        BlastRadiusGuard blastRadiusGuard = new BlastRadiusGuard();
        GuardrailConfigService guardrailConfigService =
                GuardrailPipelineSupport.guardrailConfigService(guardrailConfig);

        GuardrailPipelineSupport.Harness harness = GuardrailPipelineSupport.harness(
                scored, thresholds, null, blastRadiusGuard, guardrailConfigService, null);
        harness.withActiveProfile("alice");

        Verdict verdict = harness.decide(
                GuardrailPipelineSupport.signal("alice", testCase.command(), InputOrigin.TYPED));

        String expectedId = testCase.expectedTriggeredId();

        // The blast-radius guardrail changed the Corrective_Action / raised the score: at least ASK.
        assertThat(verdict.action())
                .as("blast-radius trigger must flag the command to at least ASK")
                .isIn(CorrectiveAction.ASK, CorrectiveAction.BLOCK);

        // The Explanation names the triggering guardrail id (Req 3.7).
        assertThat(verdict.explanation())
                .as("explanation must name the triggering guardrail id %s", expectedId)
                .contains(expectedId);

        // The persisted Audit_History record names the triggering guardrail id (Req 3.7).
        AuditHistoryDocument record = harness.decisionRecord();
        assertThat(record.getExplanation())
                .as("audit record must name the triggering guardrail id %s", expectedId)
                .contains(expectedId);
        assertThat(record.getCorrectiveAction()).isEqualTo(verdict.action().name());
    }

    // --- generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<Case> cases() {
        Arbitrary<Trigger> triggers = Arbitraries.of(Trigger.values());
        // A distinctive id suffix so the naming clause must genuinely surface the generated id.
        Arbitrary<String> suffixes = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10);
        // Base composite in the allow range so the guardrail is the sole cause of any flag.
        Arbitrary<Double> baseComposites = Arbitraries.doubles().between(0.0, 0.35);

        return Combinators.combine(triggers, suffixes, baseComposites).as(Case::of);
    }

    /** A generated triggering case: the command, the guardrail config, and the id expected to be named. */
    private record Case(
            Trigger trigger,
            String command,
            GuardrailConfig config,
            String expectedTriggeredId,
            double baseComposite) {

        static Case of(Trigger trigger, String suffix, double baseComposite) {
            return switch (trigger) {
                case PROTECTED_TARGET -> {
                    String id = "protected-path-" + suffix;
                    GuardrailConfig cfg = config(
                            List.of(new ProtectedTarget(id, TargetKind.PATH, "/etc/**", false)), List.of());
                    yield new Case(trigger, "cat /etc/passwd", cfg, id, baseComposite);
                }
                case BLOCK_ON_ACCESS -> {
                    String id = "canary-path-" + suffix;
                    GuardrailConfig cfg = config(
                            List.of(new ProtectedTarget(id, TargetKind.PATH, "/etc/**", true)), List.of());
                    yield new Case(trigger, "cat /etc/passwd", cfg, id, baseComposite);
                }
                case DESTRUCTIVE_VERB -> {
                    // The recorded id for a destructive-verb trigger is a stable guardrail marker.
                    GuardrailConfig cfg = config(List.of(), List.of("drop table"));
                    yield new Case(
                            trigger,
                            "drop table users",
                            cfg,
                            BlastRadiusGuard.DESTRUCTIVE_VERB_TRIGGER_ID,
                            baseComposite);
                }
            };
        }

        private static GuardrailConfig config(List<ProtectedTarget> targets, List<String> verbs) {
            return new GuardrailConfig(
                    1, targets, 100, verbs, 0.90, 300_000L, Map.of(), Map.of(), "admin", 0L);
        }
    }
}
