package com.intentguard.domain;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The outcome of scoring a single divergence component (Req 5.7). A component either produces a
 * score in [0.0, 1.0] with an applied weight, or is {@code excluded} with a reason recorded in
 * {@code note} (e.g. the LLM timed out, or no intent was available).
 *
 * @param id     which component produced this result
 * @param score  the component score in [0.0, 1.0], or empty when the component was excluded
 * @param weight the weight applied to this component before renormalization
 * @param note   an optional human-readable note (e.g. an exclusion reason), or {@code null}
 */
public record ComponentResult(ComponentId id, OptionalDouble score, double weight, String note) {

    public ComponentResult {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(score, "score must not be null (use OptionalDouble.empty())");
        if (score.isPresent()) {
            double s = score.getAsDouble();
            if (s < 0.0 || s > 1.0 || Double.isNaN(s)) {
                throw new IllegalArgumentException("component score must be in [0.0, 1.0]: " + s);
            }
        }
        if (weight < 0.0 || Double.isNaN(weight)) {
            throw new IllegalArgumentException("weight must be non-negative: " + weight);
        }
    }

    /** A scored component result with the given score and weight. */
    public static ComponentResult scored(ComponentId id, double score, double weight, String note) {
        return new ComponentResult(id, OptionalDouble.of(score), weight, note);
    }

    /** An excluded component result carrying the reason it was excluded. */
    public static ComponentResult excluded(ComponentId id, double weight, String reason) {
        return new ComponentResult(id, OptionalDouble.empty(), weight, reason);
    }

    public boolean isExcluded() {
        return score.isEmpty();
    }
}
