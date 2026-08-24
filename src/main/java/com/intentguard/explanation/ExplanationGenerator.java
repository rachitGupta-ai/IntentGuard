package com.intentguard.explanation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.llm.LlmService;

/**
 * Produces the plain-English {@code Explanation} for every {@code ask}/{@code block} decision
 * (Req 8.1).
 *
 * <p><strong>LLM-first, deterministic fallback.</strong> The generator first asks the
 * {@link LlmService} for explanation text. Because the LLM call is best-effort and returns
 * {@link Optional#empty()} on timeout/error (or when running with no API key), the generator falls
 * back to a {@link DeterministicExplanationTemplate} built from the ranked component contributions
 * (Req 8.4). Either path yields a non-empty Explanation that names the divergence components that
 * contributed most to the decision (Req 8.2) and states the pasted origin when a pasted event
 * contributed (Req 9.3).
 *
 * <p>The fallback text alone always satisfies Req 8.2 and Req 9.3; the LLM prompt is separately fed
 * the contributing components and the pasted/typed origin by the {@code LlmService} adapter, so
 * both paths carry the same information.
 */
@Component
public class ExplanationGenerator {

    private final LlmService llmService;

    public ExplanationGenerator(LlmService llmService) {
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null");
    }

    /**
     * Produce the Explanation for a flagged Command_Event. Prefers the LLM text and falls back to
     * the deterministic component-derived template when the LLM is unavailable.
     *
     * @param event    the flagged Command_Event
     * @param result   the divergence result with component scores and applied weights
     * @param decision the corrective decision reached (its action drives the phrasing)
     * @return a non-empty plain-English Explanation
     */
    public String explain(CommandEvent event, DivergenceResult result, Decision decision) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        Optional<String> llmText = safeLlmExplain(event, result, decision);
        if (llmText.isPresent()) {
            String text = llmText.get().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        // LLM unavailable, timed out, errored, or returned blank text: compose deterministically.
        return DeterministicExplanationTemplate.compose(event, result, decision);
    }

    /**
     * Produce the Explanation for a Command_Event whose {@code ask}/{@code block} was caused by a
     * guardrail, guaranteeing the triggering guardrail(s) are <strong>named</strong> in the text
     * (Req 2.11, 3.7). Prefers the LLM text and falls back to the deterministic template, but in
     * either path appends a clause naming any triggering guardrail id not already mentioned, so the
     * matched {@code PolicyRule} id / {@code ProtectedTarget} id / guardrail name is always present
     * even when the LLM is unavailable.
     *
     * <p>Additive overload: when {@code triggeredGuardrailIds} is {@code null} or empty this behaves
     * exactly like {@link #explain(CommandEvent, DivergenceResult, Decision)}.
     *
     * @param event                 the flagged Command_Event
     * @param result                the divergence result with component scores and applied weights
     * @param decision              the corrective decision reached (its reason code labels the
     *                              guardrail)
     * @param triggeredGuardrailIds identifiers of the guardrail(s) that triggered
     * @return a non-empty Explanation naming every triggering guardrail id
     */
    public String explain(
            CommandEvent event,
            DivergenceResult result,
            Decision decision,
            List<String> triggeredGuardrailIds) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        if (triggeredGuardrailIds == null || triggeredGuardrailIds.isEmpty()) {
            return explain(event, result, decision);
        }

        Optional<String> llmText = safeLlmExplain(event, result, decision);
        if (llmText.isPresent()) {
            String text = llmText.get().trim();
            if (!text.isEmpty()) {
                // Ensure the LLM text names the triggering guardrail(s) (Req 2.11, 3.7).
                return DeterministicExplanationTemplate.withGuardrailNaming(
                        text, decision, triggeredGuardrailIds);
            }
        }
        // LLM unavailable: compose deterministically, still naming the triggering guardrail(s).
        return DeterministicExplanationTemplate.compose(event, result, decision, triggeredGuardrailIds);
    }

    /**
     * Invoke the LLM defensively. The adapter already degrades to {@link Optional#empty()} on
     * timeout/error, but any unexpected runtime failure is also swallowed so explanation generation
     * can never throw and always yields a deterministic fallback.
     */
    private Optional<String> safeLlmExplain(CommandEvent event, DivergenceResult result, Decision decision) {
        try {
            Optional<String> text = llmService.explain(event, result, decision);
            return text == null ? Optional.empty() : text;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }
}
