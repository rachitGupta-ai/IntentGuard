package com.intentguard.scoring;

import java.util.Objects;
import java.util.OptionalDouble;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ScoringContext;
import com.intentguard.llm.LlmService;

/**
 * Semantic_Inconsistency component (Req 5.5, 5.6, 6.3, 6.4): an LLM-assisted measure of how far a
 * Command_Event's likely effect diverges from the intent it should serve.
 *
 * <h2>Behavior with graceful fallback</h2>
 * <ul>
 *   <li><b>Intent open</b> — when the {@link ScoringContext} carries intent
 *       ({@link ScoringContext#hasIntent()}), the command and intent text are sent to the
 *       {@link LlmService}. If it returns a value, that value is clamped to {@code [0,1]} and
 *       reported as the score with the configured Semantic_Inconsistency weight (Req 5.5).</li>
 *   <li><b>LLM unavailable</b> — if the {@link LlmService} returns
 *       {@link OptionalDouble#empty()} (timeout, error, or malformed output), the component returns
 *       {@link ComponentResult#excluded} with the reason {@code "llm_unavailable"}; the pipeline
 *       then renormalizes the remaining weights (Req 6.3, 6.4).</li>
 *   <li><b>No intent</b> — if no intent is present, the LLM is not called and the component returns
 *       {@link ComponentResult#excluded} with the reason {@code "no_intent"} (Req 5.6).</li>
 * </ul>
 *
 * <p>This component only depends on the {@link LlmService} contract, which already enforces the
 * tight timeout and deterministic empty-on-failure semantics, so the fallback here is a simple,
 * total mapping over its {@link OptionalDouble} result.
 */
public final class SemanticInconsistencyComponent implements DivergenceComponent {

    /** Recorded reason when no Declared_Intent (or Inferred_Intent) is available to score against. */
    static final String REASON_NO_INTENT = "no_intent";

    /** Recorded reason when the LLM_Service cannot produce a score (timeout, error, malformed). */
    static final String REASON_LLM_UNAVAILABLE = "llm_unavailable";

    private final LlmService llmService;

    public SemanticInconsistencyComponent(LlmService llmService) {
        this.llmService = Objects.requireNonNull(llmService, "llmService must not be null");
    }

    @Override
    public ComponentId id() {
        return ComponentId.SEMANTIC_INCONSISTENCY;
    }

    @Override
    public ComponentResult score(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        double weight = weightFor(ctx);

        if (!ctx.hasIntent()) {
            // No Declared_Intent nor Inferred_Intent: score from the remaining components (Req 5.6).
            return ComponentResult.excluded(id(), weight, REASON_NO_INTENT);
        }

        OptionalDouble semantic = llmService.semanticInconsistency(ctx.event(), ctx.intentText());
        if (semantic.isEmpty()) {
            // Timeout, error, or malformed output: exclude and renormalize (Req 6.3, 6.4).
            return ComponentResult.excluded(id(), weight, REASON_LLM_UNAVAILABLE);
        }

        double score = clampUnit(semantic.getAsDouble());
        return ComponentResult.scored(id(), score, weight, null);
    }

    /**
     * The weight to apply to this component. When the intent is inferred rather than declared, the
     * lower {@code inferredIntentSemanticWeight} is used (Req 14.3); otherwise the configured
     * Semantic_Inconsistency weight applies.
     */
    private double weightFor(ScoringContext ctx) {
        if (ctx.intentSource() == IntentSource.INFERRED) {
            return ctx.config().inferredIntentSemanticWeight();
        }
        return ctx.config().weightFor(id());
    }

    private static double clampUnit(double value) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
