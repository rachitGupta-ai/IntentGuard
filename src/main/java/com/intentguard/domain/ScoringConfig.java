package com.intentguard.domain;

import java.util.Map;
import java.util.Objects;

/**
 * The configuration the scoring pipeline needs to compute a {@link DivergenceResult}: the
 * configured weight for each component and the (lower) semantic weight to apply when the intent
 * is inferred rather than declared (Req 5.1, 14.3). Weights are validated non-negative; the
 * effective weights for a single scoring are those of the available components renormalized to
 * sum to 1.0.
 *
 * @param componentWeights          non-negative weight per component
 * @param inferredIntentSemanticWeight the Semantic_Inconsistency weight to use when the intent is
 *                                     inferred (must be strictly lower than the declared weight)
 */
public record ScoringConfig(
        Map<ComponentId, Double> componentWeights,
        double inferredIntentSemanticWeight) {

    public ScoringConfig {
        Objects.requireNonNull(componentWeights, "componentWeights must not be null");
        componentWeights = Map.copyOf(componentWeights);
        componentWeights.forEach((id, w) -> {
            if (w == null || w < 0.0 || Double.isNaN(w)) {
                throw new IllegalArgumentException("weight for " + id + " must be non-negative: " + w);
            }
        });
        if (inferredIntentSemanticWeight < 0.0 || Double.isNaN(inferredIntentSemanticWeight)) {
            throw new IllegalArgumentException(
                    "inferredIntentSemanticWeight must be non-negative: " + inferredIntentSemanticWeight);
        }
    }

    /** The configured weight for a component, or 0.0 if unspecified. */
    public double weightFor(ComponentId id) {
        return componentWeights.getOrDefault(id, 0.0);
    }
}
