package com.intentguard.scoring;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;

/**
 * Default {@link ScoringPipeline}. Collects a {@link ComponentResult} from each registered
 * {@link DivergenceComponent} and combines them into the composite Divergence_Score as a
 * <em>renormalized weighted sum</em> of the available (non-excluded) components.
 *
 * <h2>Composite definition (Req 5.1, 5.6, 5.7)</h2>
 * <p>Let {@code A} be the set of components that produced a score (not excluded). The composite is
 * <pre>{@code
 *   composite = sum_{i in A} (score_i * weight_i) / sum_{i in A} weight_i
 * }</pre>
 * i.e. the weights of the available components are renormalized to sum to 1.0 before being applied.
 * Because every {@code score_i} lies in [0,1] and the renormalized weights are non-negative and sum
 * to 1.0, the composite is a convex combination and is therefore guaranteed to lie in [0,1]. The
 * computation reads only the inputs (never wall-clock time, randomness, or iteration order), so it
 * is deterministic: the same inputs and configuration always yield the same composite.
 *
 * <h2>Excluded components</h2>
 * <p>A component that returns {@link ComponentResult#isExcluded()} contributes neither score nor
 * weight to the composite; its exclusion is recorded in {@link DivergenceResult#excluded()} and its
 * result (with the recorded reason) is retained in {@link DivergenceResult#components()} so the full
 * audit trail is preserved.
 *
 * <h2>All components excluded (edge case)</h2>
 * <p>When no component is available (all excluded, or the available components carry a total weight
 * of zero, which would otherwise divide by zero), the composite is defined as {@code 0.0}. This is a
 * deliberate choice: with no available evidence of divergence, the least-divergent score is the
 * conservative default, and the emptiness/exclusion is fully recorded for the decision layer to act
 * on (e.g. agent-containment or learning clamps) rather than being masked by a fabricated score.
 */
@Component
public class DefaultScoringPipeline implements ScoringPipeline {

    /** Composite used when there is no available weighted evidence (all excluded / zero weight). */
    static final double COMPOSITE_WHEN_NONE_AVAILABLE = 0.0;

    /** Registered components, sorted deterministically by their {@link ComponentId}. */
    private final List<DivergenceComponent> components;

    /** Applies the agent-risk uplift to the composite (Req 13.5); never lowers the score. */
    private final AgentRiskAdjuster agentRiskAdjuster;

    /**
     * Spring-wired constructor. The {@link AgentRiskAdjuster} raises the composite for
     * {@code AGENT} events carrying risk markers (Req 13.5).
     */
    @Autowired
    public DefaultScoringPipeline(List<DivergenceComponent> components, AgentRiskAdjuster agentRiskAdjuster) {
        Objects.requireNonNull(components, "components must not be null");
        this.agentRiskAdjuster = Objects.requireNonNull(agentRiskAdjuster, "agentRiskAdjuster must not be null");
        List<DivergenceComponent> sorted = new ArrayList<>(components);
        // Sort by component id so the collection/iteration order is stable regardless of the order
        // in which components were registered (keeps the pipeline deterministic).
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        this.components = List.copyOf(sorted);
    }

    /** Convenience constructor using a default {@link AgentRiskAdjuster}. */
    public DefaultScoringPipeline(List<DivergenceComponent> components) {
        this(components, new AgentRiskAdjuster());
    }

    @Override
    public DivergenceResult score(CommandEvent event, ScoringConfig config) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(config, "config must not be null");

        // Backward-compatible path: no resolved intent text and a default ACTIVE profile state.
        ScoringContext ctx = new ScoringContext(
                event,
                null,
                event.intentSource(),
                ProfileState.ACTIVE,
                config);
        return score(ctx);
    }

    @Override
    public DivergenceResult score(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        CommandEvent event = ctx.event();

        List<ComponentResult> results = new ArrayList<>(components.size());
        for (DivergenceComponent component : components) {
            ComponentResult result = component.score(ctx);
            Objects.requireNonNull(result, () -> "component " + component.id() + " returned null result");
            results.add(result);
        }
        DivergenceResult combined = combine(results);

        // Agent-risk uplift (Req 13.5): raise the composite when an AGENT event carries a risk
        // marker. The adjuster only ever raises the score (never lowers) and keeps it in [0,1], so
        // the renormalized weighted-sum invariant of combine() is preserved for the base composite.
        double adjusted = agentRiskAdjuster.adjust(combined.composite(), event);
        if (adjusted == combined.composite()) {
            return combined;
        }
        return new DivergenceResult(adjusted, combined.components(), combined.excluded());
    }

    /**
     * Combine per-component results into a {@link DivergenceResult} using the renormalized weighted
     * sum defined in the class Javadoc. Exposed package-private so the renormalization math can be
     * exercised directly with hand-built component results.
     *
     * @param results the per-component results (each with its applied weight)
     * @return the composite result in [0.0, 1.0] with excluded components recorded
     */
    static DivergenceResult combine(List<ComponentResult> results) {
        Objects.requireNonNull(results, "results must not be null");

        double weightedScoreSum = 0.0;
        double availableWeightSum = 0.0;
        Set<ComponentId> excluded = new LinkedHashSet<>();

        for (ComponentResult result : results) {
            if (result.isExcluded()) {
                excluded.add(result.id());
                continue;
            }
            double weight = result.weight();
            weightedScoreSum += result.score().getAsDouble() * weight;
            availableWeightSum += weight;
        }

        double composite;
        if (availableWeightSum <= 0.0) {
            // No available component carries positive weight: define the composite as the
            // conservative least-divergent value. See class Javadoc "All components excluded".
            composite = COMPOSITE_WHEN_NONE_AVAILABLE;
        } else {
            // Renormalize the available weights to sum to 1.0 by dividing by their total.
            composite = weightedScoreSum / availableWeightSum;
        }

        // Guard against tiny floating-point overshoot so the [0,1] invariant always holds.
        composite = clampUnit(composite);

        return new DivergenceResult(composite, results, excluded);
    }

    private static double clampUnit(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
