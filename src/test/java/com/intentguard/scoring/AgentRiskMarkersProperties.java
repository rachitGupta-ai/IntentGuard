package com.intentguard.scoring;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 19: Agent risk markers never lower the score.
 *
 * <p>For any two {@code AGENT} Command_Events identical except that one opens a new outbound
 * network connection, accesses a credential/secret file, or performs a privilege escalation
 * unrelated to the Declared_Intent, the Divergence_Score of the event carrying the risk marker is
 * greater than or equal to that of the event without it
 * (Validates: Requirements 13.5).
 *
 * <p>The property is exercised at the {@link AgentRiskAdjuster} level (deterministic and precise):
 * for an arbitrary base composite in [0,1] and arbitrary {@link AgentRiskMarkers}, the adjusted
 * score for an {@code AGENT} event carrying those markers must be {@code >=} the adjusted score for
 * the identical {@code AGENT} event carrying {@link AgentRiskMarkers#none()}. Two further facets are
 * checked: (b) <b>monotonicity</b> — a superset of markers never scores below any subset, so adding
 * a marker never lowers the score; and (c) the adjusted score stays within the closed unit interval
 * [0,1].
 */
class AgentRiskMarkersProperties {

    private static final double EPS = 1e-9;

    private final AgentRiskAdjuster adjuster = new AgentRiskAdjuster();

    @Property(tries = 500)
    void agentRiskMarkersNeverLowerTheScore(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double baseComposite,
            @ForAll("markerSubsetAndSuperset") MarkerPair markers) {

        // Two AGENT events, identical except for their risk markers.
        CommandEvent withoutMarkers = agentEvent(AgentRiskMarkers.none());
        CommandEvent withSubset = agentEvent(markers.subset());
        CommandEvent withSuperset = agentEvent(markers.superset());

        double none = adjuster.adjust(baseComposite, withoutMarkers);
        double subset = adjuster.adjust(baseComposite, withSubset);
        double superset = adjuster.adjust(baseComposite, withSuperset);

        // (a) Property 19: the event carrying risk markers never scores below the marker-free event.
        assertThat(subset).isGreaterThanOrEqualTo(none - EPS);
        assertThat(superset).isGreaterThanOrEqualTo(none - EPS);

        // (b) Monotonicity: adding markers (subset -> superset) never lowers the score.
        assertThat(superset).isGreaterThanOrEqualTo(subset - EPS);

        // (c) The adjusted score stays in [0,1] regardless of base or marker combination.
        assertThat(none).isBetween(0.0, 1.0);
        assertThat(subset).isBetween(0.0, 1.0);
        assertThat(superset).isBetween(0.0, 1.0);
    }

    /**
     * Generate a pair (subset, superset) of {@link AgentRiskMarkers} where every marker present in
     * the subset is also present in the superset. The superset is the OR of the subset with a set
     * of "extra" markers, guaranteeing the subset relationship so the monotonicity facet is well
     * defined across the full space of marker combinations (including equal sets and the all/none
     * extremes).
     */
    @Provide
    Arbitrary<MarkerPair> markerSubsetAndSuperset() {
        Arbitrary<Boolean> bool = Arbitraries.of(true, false);
        return Combinators.combine(bool, bool, bool, bool, bool, bool)
                .as((a, b, c, extraA, extraB, extraC) -> {
                    AgentRiskMarkers subset = new AgentRiskMarkers(a, b, c);
                    AgentRiskMarkers superset = new AgentRiskMarkers(
                            a || extraA, b || extraB, c || extraC);
                    return new MarkerPair(subset, superset);
                });
    }

    /** A pair of marker sets where {@code superset} contains every marker of {@code subset}. */
    private record MarkerPair(AgentRiskMarkers subset, AgentRiskMarkers superset) {
    }

    /** An AGENT Command_Event carrying the given risk markers; all other fields held identical. */
    private static CommandEvent agentEvent(AgentRiskMarkers markers) {
        return new CommandEvent(
                "evt-1",
                Actor.agent("svc", "alice"),
                null,
                "curl https://evil.example.com",
                "/home/alice/repo",
                "repo",
                Map.of(),
                1_710_000_000_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                markers);
    }
}
