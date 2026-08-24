package com.intentguard.scoring;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for the renormalized weighted-sum composite computed by {@link DefaultScoringPipeline}.
 * These exercise the renormalization math directly via {@link DefaultScoringPipeline#combine} with
 * hand-built {@link ComponentResult}s: all components present, one component excluded (remaining
 * weights renormalize), and the all-excluded edge case.
 */
class DefaultScoringPipelineTest {

    private static final double EPS = 1e-9;

    @Test
    void allComponentsPresentWithWeightsSummingToOneGivesPlainWeightedSum() {
        // Weights already sum to 1.0, so renormalization is a no-op and the composite is the
        // straightforward weighted sum: 0.20*0.25 + 0.40*0.20 + 0.60*0.25 + 0.80*0.30 = 0.52.
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.20, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.40, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 0.60, 0.25, null),
                ComponentResult.scored(ComponentId.SEMANTIC_INCONSISTENCY, 0.80, 0.30, null));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        assertThat(result.composite()).isCloseTo(0.52, within(EPS));
        assertThat(result.excluded()).isEmpty();
        assertThat(result.components()).hasSize(4);
    }

    @Test
    void allComponentsPresentWithWeightsNotSummingToOneAreRenormalized() {
        // Raw weights sum to 2.0; the composite must divide by that total so it stays in [0,1].
        // weighted sum = 0.5*1.0 + 1.0*1.0 = 1.5; renormalized = 1.5 / 2.0 = 0.75.
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.5, 1.0, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 1.0, 1.0, null));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        assertThat(result.composite()).isCloseTo(0.75, within(EPS));
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void oneComponentExcludedRenormalizesRemainingWeights() {
        // Semantic excluded (weight 0.30 dropped). Remaining available weights are 0.25, 0.20, 0.25
        // summing to 0.70. weighted score sum = 0.20*0.25 + 0.40*0.20 + 0.60*0.25 = 0.28.
        // composite = 0.28 / 0.70 = 0.40.
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.20, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.40, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 0.60, 0.25, null),
                ComponentResult.excluded(ComponentId.SEMANTIC_INCONSISTENCY, 0.30, "excluded: no_intent"));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        assertThat(result.composite()).isCloseTo(0.40, within(EPS));
        assertThat(result.excluded()).containsExactly(ComponentId.SEMANTIC_INCONSISTENCY);
        // The excluded component's result (with reason) is still recorded for the audit trail.
        assertThat(result.components()).hasSize(4);
        ComponentResult semantic = result.components().stream()
                .filter(c -> c.id() == ComponentId.SEMANTIC_INCONSISTENCY)
                .findFirst()
                .orElseThrow();
        assertThat(semantic.isExcluded()).isTrue();
        assertThat(semantic.note()).isEqualTo("excluded: no_intent");
    }

    @Test
    void renormalizationIsInvariantToTheExcludedComponentScore() {
        // Excluding a component must remove both its score and its weight; the composite of the
        // remaining components equals what a fresh weighted sum over just those components gives,
        // regardless of any (unused) score the excluded slot might have carried.
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.10, 0.5, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.90, 0.5, null),
                ComponentResult.excluded(ComponentId.SEMANTIC_INCONSISTENCY, 100.0, "irrelevant"));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        // 0.10*0.5 + 0.90*0.5 = 0.50; renormalized by 1.0 => 0.50.
        assertThat(result.composite()).isCloseTo(0.50, within(EPS));
        assertThat(result.excluded()).containsExactly(ComponentId.SEMANTIC_INCONSISTENCY);
    }

    @Test
    void allComponentsExcludedYieldsZeroComposite() {
        List<ComponentResult> results = List.of(
                ComponentResult.excluded(ComponentId.SEQUENCE_SURPRISE, 0.25, "excluded: a"),
                ComponentResult.excluded(ComponentId.CONTEXT_MISMATCH, 0.20, "excluded: b"),
                ComponentResult.excluded(ComponentId.BEHAVIORAL_DEVIATION, 0.25, "excluded: c"),
                ComponentResult.excluded(ComponentId.SEMANTIC_INCONSISTENCY, 0.30, "excluded: d"));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        assertThat(result.composite()).isEqualTo(0.0);
        assertThat(result.excluded()).containsExactlyInAnyOrder(
                ComponentId.SEQUENCE_SURPRISE,
                ComponentId.CONTEXT_MISMATCH,
                ComponentId.BEHAVIORAL_DEVIATION,
                ComponentId.SEMANTIC_INCONSISTENCY);
    }

    @Test
    void availableComponentsWithZeroTotalWeightYieldZeroComposite() {
        // Degenerate: the only available component carries zero weight, so there is no weighted
        // evidence to divide by. The composite defaults to the conservative 0.0 rather than NaN.
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.9, 0.0, null));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        assertThat(result.composite()).isEqualTo(0.0);
        assertThat(Double.isNaN(result.composite())).isFalse();
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void compositeStaysInUnitIntervalWhenAllScoresAreMaximal() {
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 1.0, 0.25, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 1.0, 0.20, null),
                ComponentResult.scored(ComponentId.BEHAVIORAL_DEVIATION, 1.0, 0.25, null),
                ComponentResult.scored(ComponentId.SEMANTIC_INCONSISTENCY, 1.0, 0.30, null));

        DivergenceResult result = DefaultScoringPipeline.combine(results);

        // All-max scores must renormalize to exactly 1.0 (the upper bound), never overshoot.
        assertThat(result.composite()).isEqualTo(1.0);
    }

    @Test
    void combineIsDeterministicAcrossRepeatedComputations() {
        List<ComponentResult> results = List.of(
                ComponentResult.scored(ComponentId.SEQUENCE_SURPRISE, 0.33, 0.4, null),
                ComponentResult.scored(ComponentId.CONTEXT_MISMATCH, 0.66, 0.6, null));

        double first = DefaultScoringPipeline.combine(results).composite();
        double second = DefaultScoringPipeline.combine(results).composite();
        double third = DefaultScoringPipeline.combine(results).composite();

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    void pipelineCollectsEveryRegisteredComponentAndComputesRenormalizedComposite() {
        // Register components in a non-sorted order to confirm the pipeline orders them
        // deterministically and still collects a result from each.
        ScoringConfig config = new ScoringConfig(
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15);

        DivergenceComponent behavioral = fixed(ComponentId.BEHAVIORAL_DEVIATION, 0.60, 0.25);
        DivergenceComponent semantic = excludedComp(ComponentId.SEMANTIC_INCONSISTENCY, 0.30, "no_intent");
        DivergenceComponent context = fixed(ComponentId.CONTEXT_MISMATCH, 0.40, 0.20);
        DivergenceComponent sequence = fixed(ComponentId.SEQUENCE_SURPRISE, 0.20, 0.25);

        ScoringPipeline pipeline = new DefaultScoringPipeline(
                List.of(behavioral, semantic, context, sequence));

        DivergenceResult result = pipeline.score(sampleEvent(), config);

        // Semantic excluded; remaining renormalize: 0.28 / 0.70 = 0.40 (same as combine() test).
        assertThat(result.composite()).isCloseTo(0.40, within(EPS));
        assertThat(result.components()).hasSize(4);
        assertThat(result.excluded()).containsExactly(ComponentId.SEMANTIC_INCONSISTENCY);
    }

    @Test
    void pipelineScoreIsDeterministicRegardlessOfRegistrationOrder() {
        ScoringConfig config = new ScoringConfig(
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.5,
                        ComponentId.CONTEXT_MISMATCH, 0.5),
                0.15);

        DivergenceComponent sequence = fixed(ComponentId.SEQUENCE_SURPRISE, 0.10, 0.5);
        DivergenceComponent context = fixed(ComponentId.CONTEXT_MISMATCH, 0.90, 0.5);

        double orderA = new DefaultScoringPipeline(List.of(sequence, context))
                .score(sampleEvent(), config).composite();
        double orderB = new DefaultScoringPipeline(List.of(context, sequence))
                .score(sampleEvent(), config).composite();

        assertThat(orderA).isEqualTo(orderB);
    }

    private static CommandEvent sampleEvent() {
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                null,
                "git status",
                "/home/alice/repo",
                "repo",
                Map.of(),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                null);
    }

    private static DivergenceComponent fixed(ComponentId id, double score, double weight) {
        return new DivergenceComponent() {
            @Override
            public ComponentId id() {
                return id;
            }

            @Override
            public ComponentResult score(ScoringContext ctx) {
                return ComponentResult.scored(id, score, weight, null);
            }
        };
    }

    private static DivergenceComponent excludedComp(ComponentId id, double weight, String reason) {
        return new DivergenceComponent() {
            @Override
            public ComponentId id() {
                return id;
            }

            @Override
            public ComponentResult score(ScoringContext ctx) {
                return ComponentResult.excluded(id, weight, reason);
            }
        };
    }
}
