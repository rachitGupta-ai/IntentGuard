package com.intentguard.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusGuard;
import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.Verdict;
import com.intentguard.persistence.AuditHistoryDocument;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 17: Indeterminate blast radius fails safe to ASK and is
 * recorded.
 *
 * <p>For any event whose blast radius / protected-target access is indeterminate, the
 * Corrective_Action floor is at least {@code ASK} and an indeterminate-evaluation record is
 * persisted (<strong>Validates: Requirements 3.8</strong>).
 *
 * <p>The property drives the fully-wired {@link PipelineDecisionProvider} end-to-end through a real
 * {@link GuardrailDecisionEngine} and {@link BlastRadiusGuard}, with the LLM unavailable so the
 * deterministic naming path must surface the indeterminate marker. It generates a blank /
 * whitespace-only command — the canonical case whose blast radius cannot be estimated — over an
 * allow-range base composite, so the fail-safe floor is the sole cause of any flag. It then asserts
 * the decision is at least {@code ASK} and that the persisted Audit_History record notes the
 * indeterminate blast-radius trigger.
 */
class IndeterminateBlastRadiusProperties {

    @Property(tries = 200)
    void indeterminateBlastRadiusFailsSafeToAskAndIsRecorded(
            @ForAll("blankCommands") String command,
            @ForAll("allowRangeComposites") double baseComposite) {

        ThresholdConfiguration thresholds = GuardrailPipelineSupport.thresholds(0.4, 0.7, 1);
        DivergenceResult scored = GuardrailPipelineSupport.scoredResult(baseComposite);
        // A default config (no protected targets, no destructive verbs) so the blank command's
        // unknown blast radius is the sole trigger and it fails safe (Req 3.8).
        GuardrailConfig guardrailConfig = GuardrailConfig.defaults("admin", 0L);

        BlastRadiusGuard blastRadiusGuard = new BlastRadiusGuard();
        GuardrailConfigService guardrailConfigService =
                GuardrailPipelineSupport.guardrailConfigService(guardrailConfig);

        GuardrailPipelineSupport.Harness harness = GuardrailPipelineSupport.harness(
                scored, thresholds, null, blastRadiusGuard, guardrailConfigService, null);
        harness.withActiveProfile("alice");

        Verdict verdict = harness.decide(
                GuardrailPipelineSupport.signal("alice", command, InputOrigin.TYPED));

        // Fail-safe: the indeterminate blast radius raises the floor to at least ASK (Req 3.8).
        assertThat(verdict.action().ordinal())
                .as("indeterminate blast radius must raise the floor to at least ASK")
                .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());

        // The indeterminate evaluation is recorded and named in the Audit_History (Req 3.8).
        AuditHistoryDocument record = harness.decisionRecord();
        assertThat(record.getExplanation())
                .as("audit record must note the indeterminate blast-radius trigger")
                .contains(BlastRadiusGuard.INDETERMINATE_TRIGGER_ID);
        assertThat(verdict.explanation())
                .as("explanation must note the indeterminate blast-radius trigger")
                .contains(BlastRadiusGuard.INDETERMINATE_TRIGGER_ID);
        assertThat(record.getCorrectiveAction()).isEqualTo(verdict.action().name());
    }

    // --- generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<String> blankCommands() {
        // Empty or whitespace-only commands: their blast radius cannot be estimated (indeterminate).
        return Arbitraries.strings().withChars(' ', '\t').ofMinLength(0).ofMaxLength(6);
    }

    @Provide
    Arbitrary<Double> allowRangeComposites() {
        return Arbitraries.doubles().between(0.0, 0.35);
    }
}
