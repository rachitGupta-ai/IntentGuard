package com.intentguard.llm;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;

/**
 * LLM_Service contract backed by Google Gemini (Req 6). Both operations are best-effort: they are
 * bounded by a timeout tighter than the 2-second decision budget and return an empty result on
 * timeout, error, or malformed output so callers can fall back deterministically.
 */
public interface LlmService {

    /**
     * Scores the Semantic_Inconsistency between a Command_Event's likely effect and the given
     * intent text (Req 6.1, 6.2). The returned value is clamped to {@code [0.0, 1.0]}.
     *
     * @param event      the Command_Event under evaluation
     * @param intentText the Declared_Intent (or Inferred_Intent) text to score against
     * @return the semantic-inconsistency score in {@code [0.0, 1.0]}, or
     *         {@link OptionalDouble#empty()} on timeout, error, malformed output, or absent intent
     */
    OptionalDouble semanticInconsistency(CommandEvent event, String intentText);

    /**
     * Produces a short plain-English Explanation for an {@code ask}/{@code block} decision (Req
     * 8.1). Returns {@link Optional#empty()} on timeout or error so the caller can fall back to a
     * deterministic component-derived template.
     *
     * @param event    the Command_Event that was flagged
     * @param result   the divergence result with component scores and weights
     * @param decision the corrective decision reached
     * @return one or two plain-English sentences, or {@link Optional#empty()} on timeout/error
     */
    Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision);

    /**
     * Summarizes a recent command window into a short natural-language Inferred_Intent (Req 14.1).
     * Used only when inferred-intent estimation is enabled and no Declared_Intent is present, to
     * estimate what the user is trying to do from their recent command statistics.
     *
     * <p>Best-effort like the other operations: bounded by a timeout tighter than the decision
     * budget and returning {@link Optional#empty()} on timeout, error, malformed output, or an
     * empty command window, so the caller degrades gracefully (no Inferred_Intent, Semantic
     * excluded).
     *
     * <p>A {@code default} implementation returning {@link Optional#empty()} is provided so
     * implementations that do not support summarization (test stubs, deterministic scenario stubs)
     * keep compiling and simply never produce an Inferred_Intent.
     *
     * @param recentCommands the recent command window (most-representative commands first)
     * @return a short Inferred_Intent phrase, or {@link Optional#empty()} when unavailable
     */
    default Optional<String> summarizeIntent(List<String> recentCommands) {
        return Optional.empty();
    }
}
