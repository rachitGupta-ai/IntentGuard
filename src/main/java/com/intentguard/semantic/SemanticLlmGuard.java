package com.intentguard.semantic;

import java.util.Objects;
import java.util.OptionalDouble;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.llm.LlmResponseParser;
import com.intentguard.llm.LlmService;

/**
 * Semantic LLM guard (Req 8.5, 8.6), enabled only when
 * {@code intentguard.guardrails.semantic.enabled=true}.
 *
 * <p>This guard reuses the firewall's existing exclude-on-malformed behaviour: a malformed LLM
 * response for a semantic guardrail is <strong>excluded</strong> from the Divergence_Score (never
 * treated as a signal, Req 8.5) and the malformed-response error is recorded (Req 8.6). Two
 * evaluation paths share the same guarantee:
 *
 * <ul>
 *   <li>{@link #evaluate(CommandEvent, String)} calls the {@link LlmService}, whose
 *       {@code semanticInconsistency} already returns {@link OptionalDouble#empty()} on timeout,
 *       error, or malformed output — an empty result is treated as malformed-and-excluded;</li>
 *   <li>{@link #evaluateRaw(String)} parses a raw model response with the same
 *       {@link LlmResponseParser} the firewall uses, so malformed raw output (non-JSON, missing or
 *       non-numeric score, NaN/Inf) is excluded and recorded deterministically without any
 *       network.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.semantic.enabled", havingValue = "true")
public class SemanticLlmGuard {

    private final LlmService llmService;

    public SemanticLlmGuard(LlmService llmService) {
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null");
    }

    /**
     * Scores the semantic inconsistency of an event against its intent through the
     * {@link LlmService}, excluding an unusable (malformed / timed-out / errored) response from the
     * Divergence_Score.
     *
     * @param event      the Command_Event under evaluation, must not be {@code null}
     * @param intentText the intent text to score against
     * @return {@link MalformedLlmResult#usable(double)} with the score when the response is usable,
     *         or {@link MalformedLlmResult#malformed()} when it is excluded (Req 8.5, 8.6)
     */
    public MalformedLlmResult evaluate(CommandEvent event, String intentText) {
        Objects.requireNonNull(event, "event must not be null");
        OptionalDouble score = llmService.semanticInconsistency(event, intentText);
        return score.isPresent()
                ? MalformedLlmResult.usable(score.getAsDouble())
                : MalformedLlmResult.excluded();
    }

    /**
     * Classifies a raw semantic-guardrail model response using the firewall's
     * {@link LlmResponseParser}. Malformed output is excluded from the score and its error recorded.
     *
     * @param rawResponse the raw text returned by the model (may be {@code null} or prose-wrapped)
     * @return {@link MalformedLlmResult#usable(double)} when the response parses to a clamped score,
     *         or {@link MalformedLlmResult#malformed()} when it is malformed and excluded
     */
    public MalformedLlmResult evaluateRaw(String rawResponse) {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(rawResponse);
        return score.isPresent()
                ? MalformedLlmResult.usable(score.getAsDouble())
                : MalformedLlmResult.excluded();
    }
}
