package com.intentguard;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Smoke property confirming jqwik is wired into the JUnit Platform build. This exercises the
 * property-testing harness that later correctness properties (Properties 1-21) will rely on.
 */
class ScaffoldingProperties {

    // A trivial bounded property mirroring the shape of the divergence-score domain [0.0, 1.0].
    @Property
    void clampedScoresStayInUnitInterval(@ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {
        double clamped = Math.max(0.0, Math.min(1.0, score));
        assertThat(clamped).isBetween(0.0, 1.0);
        assertThat(clamped).isEqualTo(score);
    }
}
