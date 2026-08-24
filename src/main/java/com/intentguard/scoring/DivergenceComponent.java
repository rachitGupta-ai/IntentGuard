package com.intentguard.scoring;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.ScoringContext;

/**
 * A single divergence-scoring component (Req 5). Each component computes one facet of how far a
 * Command_Event diverges from authorized intent and normal behavior, returning a score in
 * [0.0, 1.0] with its applied weight, or {@link ComponentResult#excluded} when it cannot produce a
 * score (for example the LLM is unavailable, or no intent is present for Semantic_Inconsistency).
 *
 * <p>The concrete components (Sequence_Surprise, Context_Mismatch, Behavioral_Deviation,
 * Semantic_Inconsistency) are implemented separately; the {@link ScoringPipeline} depends only on
 * this contract so that components can be registered and scored uniformly and deterministically.
 */
public interface DivergenceComponent {

    /** The identifier of the component this instance computes. */
    ComponentId id();

    /**
     * Score this component for the given {@link ScoringContext}. Implementations MUST return a
     * {@link ComponentResult} whose {@code id} equals {@link #id()}, carrying either a score in
     * [0.0, 1.0] with the applied weight, or an exclusion with a recorded reason.
     *
     * @param ctx the immutable scoring input (event, intent, profile state, config)
     * @return the component's result, never {@code null}
     */
    ComponentResult score(ScoringContext ctx);
}
