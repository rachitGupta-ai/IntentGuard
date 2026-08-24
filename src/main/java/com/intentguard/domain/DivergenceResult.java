package com.intentguard.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The result of the scoring pipeline: the composite Divergence_Score together with every
 * component's score and applied weight and the set of components that were excluded (Req 5.1,
 * 5.6, 5.7).
 *
 * @param composite  the composite Divergence_Score in [0.0, 1.0]
 * @param components the per-component results (scores and applied weights)
 * @param excluded   the components excluded from this scoring (a subset of {@code components})
 */
public record DivergenceResult(
        double composite,
        List<ComponentResult> components,
        Set<ComponentId> excluded) {

    public DivergenceResult {
        if (composite < 0.0 || composite > 1.0 || Double.isNaN(composite)) {
            throw new IllegalArgumentException("composite must be in [0.0, 1.0]: " + composite);
        }
        Objects.requireNonNull(components, "components must not be null");
        Objects.requireNonNull(excluded, "excluded must not be null");
        components = List.copyOf(components);
        excluded = Set.copyOf(excluded);
    }
}
