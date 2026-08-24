package com.intentguard.explanation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.decision.GuardrailReasonCodes;
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
import com.intentguard.llm.LlmService;

/**
 * Unit tests for the deterministic guardrail-naming overloads added to
 * {@link DeterministicExplanationTemplate} and {@link ExplanationGenerator} (Req 2.11, 3.7).
 *
 * <p>They assert that when the LLM is unavailable the deterministic Explanation still <strong>names
 * the triggering guardrail id</strong> — the matched {@code PolicyRule} id for a policy trigger and
 * the {@code ProtectedTarget} id for a blast-radius trigger — so a flagged decision is always
 * traceable to the guardrail that caused it even without the LLM. A companion test confirms the LLM
 * path is likewise augmented so the id is present regardless of the model text.
 */
class GuardrailExplanationNamingTest {

    /** Stub LlmService returning a preset optional explanation; semantic scoring is unused. */
    private static final class StubLlmService implements LlmService {
        private final Optional<String> explanation;

        StubLlmService(Optional<String> explanation) {
            this.explanation = explanation;
        }

        @Override
        public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
            return explanation;
        }
    }

    private static CommandEvent event() {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                "sess-1",
                "rm -rf /",
                "/home/alice/project",
                "project",
                Map.of("PATH", "/usr/bin"),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.DECLARED,
                null);
    }

    private static DivergenceResult result() {
        List<ComponentResult> components = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.60, 0.25, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 0.90, 0.25, null));
        return new DivergenceResult(0.72, components, Set.of());
    }

    @Test
    void deterministicTemplateNamesPolicyRuleIdWhenLlmUnavailable() {
        Decision block = new Decision(CorrectiveAction.BLOCK, 0.72, GuardrailReasonCodes.POLICY_DENY);
        ExplanationGenerator generator = new ExplanationGenerator(new StubLlmService(Optional.empty()));

        String explanation =
                generator.explain(event(), result(), block, List.of("deny-rm-rf-root"));

        assertThat(explanation)
                .as("policy trigger explanation must name the matched PolicyRule id: %s", explanation)
                .contains("deny-rm-rf-root");
        // It should still name the guardrail reason in plain English.
        assertThat(explanation).contains(GuardrailReasonCodes.label(GuardrailReasonCodes.POLICY_DENY));
    }

    @Test
    void deterministicTemplateNamesBlastRadiusTargetIdWhenLlmUnavailable() {
        Decision block = new Decision(
                CorrectiveAction.BLOCK, 0.72, GuardrailReasonCodes.BLAST_RADIUS_BLOCK_ON_ACCESS);
        ExplanationGenerator generator = new ExplanationGenerator(new StubLlmService(Optional.empty()));

        String explanation = generator.explain(event(), result(), block, List.of("prod-db"));

        assertThat(explanation)
                .as("blast-radius trigger explanation must name the ProtectedTarget id: %s", explanation)
                .contains("prod-db");
        assertThat(explanation)
                .contains(GuardrailReasonCodes.label(GuardrailReasonCodes.BLAST_RADIUS_BLOCK_ON_ACCESS));
    }

    @Test
    void composeOverloadNamesTriggeringGuardrailDirectly() {
        Decision ask = new Decision(
                CorrectiveAction.ASK, 0.55, GuardrailReasonCodes.BLAST_RADIUS_ASK);

        String explanation = DeterministicExplanationTemplate.compose(
                event(), result(), ask, List.of("ssh-keys"));

        assertThat(explanation).isNotBlank();
        assertThat(explanation).contains("ssh-keys");
    }

    @Test
    void composeOverloadWithoutTriggersMatchesBaseTemplate() {
        Decision ask = new Decision(CorrectiveAction.ASK, 0.55, "THRESHOLD_ASK");

        String base = DeterministicExplanationTemplate.compose(event(), result(), ask);
        String emptyTriggers =
                DeterministicExplanationTemplate.compose(event(), result(), ask, List.of());

        assertThat(emptyTriggers)
                .as("an empty trigger list must not change the base Explanation")
                .isEqualTo(base);
    }

    @Test
    void llmPathIsAugmentedToNameTheGuardrailId() {
        String llmText = "Blocked: this command is dangerous.";
        ExplanationGenerator generator = new ExplanationGenerator(new StubLlmService(Optional.of(llmText)));
        Decision block = new Decision(CorrectiveAction.BLOCK, 0.72, GuardrailReasonCodes.POLICY_DENY);

        String explanation =
                generator.explain(event(), result(), block, List.of("deny-rm-rf-root"));

        assertThat(explanation)
                .as("even LLM text must be augmented to name the guardrail id: %s", explanation)
                .contains("deny-rm-rf-root");
        assertThat(explanation).startsWith(llmText);
    }
}
