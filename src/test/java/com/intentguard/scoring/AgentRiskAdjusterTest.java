package com.intentguard.scoring;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for {@link AgentRiskAdjuster} (Req 13.5): agent risk markers raise the composite
 * Divergence_Score and never lower it, the adjusted score stays in [0,1], human events are
 * unaffected, and the adjustment is deterministic.
 */
class AgentRiskAdjusterTest {

    private static final double EPS = 1e-9;

    private final AgentRiskAdjuster adjuster = new AgentRiskAdjuster();

    @Test
    void humanEventIsUnaffectedEvenWithRiskMarkersSet() {
        // Markers are only meaningful for AGENT actors; a HUMAN event must be returned unchanged.
        CommandEvent human = event(Actor.human("alice"), allMarkers());
        for (double base : new double[] {0.0, 0.1, 0.42, 0.9, 1.0}) {
            assertThat(adjuster.adjust(base, human)).isEqualTo(base);
        }
    }

    @Test
    void agentEventWithNoMarkersIsUnchanged() {
        CommandEvent agent = event(Actor.agent("svc", "alice"), AgentRiskMarkers.none());
        for (double base : new double[] {0.0, 0.3, 0.75, 1.0}) {
            assertThat(adjuster.adjust(base, agent)).isEqualTo(base);
        }
    }

    @Test
    void eachSingleMarkerRaisesTheCompositeForAnAgent() {
        double base = 0.40;
        double baseline = adjuster.adjust(base, event(Actor.agent("svc", "alice"), AgentRiskMarkers.none()));

        double outbound = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(true, false, false)));
        double secret = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(false, true, false)));
        double privesc = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(false, false, true)));

        assertThat(outbound).isGreaterThan(baseline);
        assertThat(secret).isGreaterThan(baseline);
        assertThat(privesc).isGreaterThan(baseline);
    }

    @Test
    void moreMarkersNeverLowerTheScore() {
        double base = 0.30;
        double one = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(true, false, false)));
        double two = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(true, true, false)));
        double three = adjuster.adjust(base, event(Actor.agent("svc", "alice"),
                new AgentRiskMarkers(true, true, true)));

        assertThat(two).isGreaterThanOrEqualTo(one);
        assertThat(three).isGreaterThanOrEqualTo(two);
    }

    @Test
    void adjustedScoreNeverExceedsOneEvenWithAllMarkersAtMaxBase() {
        double adjusted = adjuster.adjust(1.0, event(Actor.agent("svc", "alice"), allMarkers()));
        assertThat(adjusted).isEqualTo(1.0);
    }

    @Test
    void adjustedScoreStaysInUnitIntervalAcrossBasesAndMarkerCombos() {
        for (double base : new double[] {0.0, 0.2, 0.5, 0.8, 1.0}) {
            for (boolean a : new boolean[] {false, true}) {
                for (boolean b : new boolean[] {false, true}) {
                    for (boolean c : new boolean[] {false, true}) {
                        CommandEvent agent = event(Actor.agent("svc", "alice"),
                                new AgentRiskMarkers(a, b, c));
                        double adjusted = adjuster.adjust(base, agent);
                        assertThat(adjusted).isBetween(0.0, 1.0);
                        // Never lowers.
                        assertThat(adjusted).isGreaterThanOrEqualTo(base - EPS);
                    }
                }
            }
        }
    }

    @Test
    void adjustmentIsDeterministic() {
        CommandEvent agent = event(Actor.agent("svc", "alice"), allMarkers());
        double first = adjuster.adjust(0.42, agent);
        double second = adjuster.adjust(0.42, agent);
        double third = adjuster.adjust(0.42, agent);
        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    private static AgentRiskMarkers allMarkers() {
        return new AgentRiskMarkers(true, true, true);
    }

    private static CommandEvent event(Actor actor, AgentRiskMarkers markers) {
        return new CommandEvent(
                "evt-1",
                actor,
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
