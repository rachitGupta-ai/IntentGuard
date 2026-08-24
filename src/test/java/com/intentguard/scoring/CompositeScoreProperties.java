package com.intentguard.scoring;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.DivergenceResult;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-semantic-firewall, Property 2: Composite score is a bounded, deterministic
 * renormalized weighted sum.
 *
 * <p>For any set of component scores in [0,1], any valid non-negative weights, and any subset of
 * components excluded (including when no intent is available or the LLM is unavailable), the
 * composite Divergence_Score equals the weighted sum of the available components using weights
 * renormalized to sum to 1.0, lies in [0.0, 1.0], and is identical across repeated computations
 * with the same inputs. When a component is excluded, that exclusion is recorded
 * (Validates: Requirements 5.1, 5.6, 6.3, 6.4).
 *
 * <p>The generator produces, for each of the four {@link ComponentId}s, either a scored result
 * (score drawn from [0,1], non-negative weight — including zero) or an excluded result, so the
 * input space covers every subset of exclusions from none to all four (the latter models "no
 * intent available" / "LLM unavailable"), as well as the degenerate zero-total-weight case. It then
 * calls {@link DefaultScoringPipeline#combine} and checks the composite against an independently
 * recomputed renormalized weighted sum, the [0,1] bound, that every excluded id is recorded, and
 * determinism across repeated computation with identical inputs.
 */
class CompositeScoreProperties {

    private static final double EPS = 1e-9;

    @Property(tries = 500)
    void compositeIsBoundedDeterministicRenormalizedWeightedSum(
            @ForAll("componentResultSets") List<ComponentResult> results) {

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        // (a) Composite lies in the closed unit interval [0.0, 1.0].
        assertThat(result.composite()).isBetween(0.0, 1.0);
        assertThat(Double.isNaN(result.composite())).isFalse();

        // (b) Composite equals the independently-recomputed renormalized weighted sum of the
        // available (non-excluded) components, matching the implementation's zero-total-weight
        // convention of 0.0.
        double weightedScoreSum = 0.0;
        double availableWeightSum = 0.0;
        for (ComponentResult cr : results) {
            if (cr.isExcluded()) {
                continue;
            }
            weightedScoreSum += cr.score().getAsDouble() * cr.weight();
            availableWeightSum += cr.weight();
        }
        double expected = availableWeightSum <= 0.0 ? 0.0 : weightedScoreSum / availableWeightSum;
        assertThat(result.composite()).isCloseTo(expected, within(EPS));

        // (c) Every excluded component id is recorded in the result's excluded set.
        List<ComponentId> expectedExcluded = results.stream()
                .filter(ComponentResult::isExcluded)
                .map(ComponentResult::id)
                .toList();
        assertThat(result.excluded()).containsExactlyInAnyOrderElementsOf(expectedExcluded);

        // (d) Determinism: recomputing with the same inputs yields an identical composite.
        double repeated = DefaultScoringPipeline.combine(results).composite();
        assertThat(repeated).isEqualTo(result.composite());
    }

    /**
     * For each of the four {@link ComponentId}s, generate either a scored result (score in [0,1],
     * non-negative weight including zero) or an excluded result. Combining the four independent
     * per-component arbitraries lets jqwik explore every subset of exclusions and a wide range of
     * weight/score combinations, including all-excluded and zero-total-weight edge cases.
     */
    @Provide
    Arbitrary<List<ComponentResult>> componentResultSets() {
        Arbitrary<ComponentResult> sequence = componentResult(ComponentId.SEQUENCE_SURPRISE);
        Arbitrary<ComponentResult> context = componentResult(ComponentId.CONTEXT_MISMATCH);
        Arbitrary<ComponentResult> behavioral = componentResult(ComponentId.BEHAVIORAL_DEVIATION);
        Arbitrary<ComponentResult> semantic = componentResult(ComponentId.SEMANTIC_INCONSISTENCY);

        return Combinators.combine(sequence, context, behavioral, semantic)
                .as((a, b, c, d) -> {
                    List<ComponentResult> list = new ArrayList<>(4);
                    list.add(a);
                    list.add(b);
                    list.add(c);
                    list.add(d);
                    return list;
                });
    }

    /** A per-component arbitrary: either a scored result or an excluded result for {@code id}. */
    private static Arbitrary<ComponentResult> componentResult(ComponentId id) {
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);
        // Non-negative weights, including zero, to exercise the zero-total-weight edge case.
        Arbitrary<Double> weights = Arbitraries.doubles().between(0.0, 10.0);

        Arbitrary<ComponentResult> scored = Combinators.combine(scores, weights)
                .as((score, weight) -> ComponentResult.scored(id, score, weight, null));
        Arbitrary<ComponentResult> excluded = weights
                .map(weight -> ComponentResult.excluded(id, weight, "excluded: unavailable"));

        // Bias toward scored results but reliably include exclusions (and thus all-excluded sets).
        return Arbitraries.oneOf(scored, scored, excluded);
    }
}
