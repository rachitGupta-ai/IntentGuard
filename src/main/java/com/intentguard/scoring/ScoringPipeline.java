package com.intentguard.scoring;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;

/**
 * Computes the composite Divergence_Score for a Command_Event as the weighted sum of the available
 * divergence components, with the weights of the available (non-excluded) components renormalized
 * to sum to 1.0 (Req 5.1, 5.6, 5.7).
 *
 * <p>The result records every component's score and applied weight and the set of components that
 * were excluded, and is deterministic given the same inputs and configuration.
 */
public interface ScoringPipeline {

    /**
     * Score the given event under the given configuration.
     *
     * @param event  the Command_Event to score
     * @param config the scoring configuration (per-component weights)
     * @return the composite result in [0.0, 1.0] with per-component scores, applied weights, and
     *         the set of excluded components
     */
    DivergenceResult score(CommandEvent event, ScoringConfig config);

    /**
     * Score the given fully-populated {@link ScoringContext}. Unlike {@link #score(CommandEvent,
     * ScoringConfig)}, this overload lets the caller supply the resolved intent text/source and
     * Behavioral_Profile state so intent-aware components (e.g. Semantic_Inconsistency) can run.
     * This is the entry point the full ingest &rarr; scoring &rarr; decision pipeline uses (Task
     * 13.1).
     *
     * <p>The default implementation delegates to {@link #score(CommandEvent, ScoringConfig)} using
     * only the context's event and config; implementations that support intent-aware scoring should
     * override it to honor the context's intent text, intent source, and profile state.
     *
     * @param ctx the immutable scoring input (event, intent text/source, profile state, config)
     * @return the composite result in [0.0, 1.0] with per-component scores, applied weights, and
     *         the set of excluded components
     */
    default DivergenceResult score(ScoringContext ctx) {
        return score(ctx.event(), ctx.config());
    }
}
