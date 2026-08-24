package com.intentguard.decision;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-semantic-firewall, Property 6: Learning-state profiles never block.
 *
 * <p>For any profile whose event count is below the configured minimum, the profile is in the
 * learning state (and that state is recorded on each score), and any Divergence_Score that would
 * otherwise fall in the block range is downgraded to the ask Corrective_Action
 * (Validates: Requirements 3.3, 3.4).
 *
 * <p>The Decision Engine receives the learning state directly as a {@link ProfileState}
 * ({@code LEARNING} while the profile holds fewer than {@code learningMinEvents} events). This
 * property drives the engine with {@code ProfileState.LEARNING} over an arbitrary Divergence_Score
 * in [0,1] and arbitrary valid thresholds, asserting the resulting action is <em>never</em>
 * {@code BLOCK}. A non-tamper {@code HUMAN} command ({@code "ls -la"}) is used so neither the tamper
 * override nor agent containment fires, isolating the learning clamp. For contrast the same
 * block-range score against an {@code ACTIVE} profile is asserted to block, showing the clamp is
 * what makes the difference.
 */
class LearningClampProperties {

    private final DefaultDecisionEngine engine = new DefaultDecisionEngine(new TamperClassifier());

    @Property(tries = 200)
    void learningStateProfilesNeverBlock(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundA,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double boundB) {

        double askThreshold = Math.min(boundA, boundB);
        double blockThreshold = Math.max(boundA, boundB);
        ThresholdConfiguration cfg = config(askThreshold, blockThreshold);

        CommandEvent event = humanEvent();
        DivergenceResult result = result(score);

        Decision learning = engine.decide(event, result, cfg, ProfileState.LEARNING, true);

        // Core invariant (Req 3.4): a LEARNING profile is never blocked, whatever the score.
        assertThat(learning.action()).isNotEqualTo(CorrectiveAction.BLOCK);
        assertThat(learning.action()).isIn(CorrectiveAction.ALLOW, CorrectiveAction.ASK);

        // The score the decision was based on is preserved (the action is clamped, not the score).
        assertThat(learning.score()).isEqualTo(score);

        if (score >= blockThreshold) {
            // A block-range score is downgraded to ask and the clamp is the recorded reason.
            assertThat(learning.action()).isEqualTo(CorrectiveAction.ASK);
            assertThat(learning.reasonCode()).isEqualTo(DefaultDecisionEngine.REASON_LEARNING_CLAMP);
        } else if (score >= askThreshold) {
            // Ask-range scores behave normally under LEARNING (no clamp needed).
            assertThat(learning.action()).isEqualTo(CorrectiveAction.ASK);
            assertThat(learning.reasonCode()).isEqualTo(DefaultDecisionEngine.REASON_THRESHOLD_ASK);
        } else {
            // Allow-range scores behave normally under LEARNING.
            assertThat(learning.action()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(learning.reasonCode()).isEqualTo(DefaultDecisionEngine.REASON_THRESHOLD_ALLOW);
        }

        // Contrast: the same block-range score against an ACTIVE profile DOES block, confirming the
        // learning clamp is precisely what prevents the block. Guard on score > askThreshold too so
        // the block range is genuinely non-empty (when askThreshold == blockThreshold a score equal
        // to both still falls in the block range).
        if (score >= blockThreshold) {
            Decision active = engine.decide(event, result, cfg, ProfileState.ACTIVE, true);
            assertThat(active.action()).isEqualTo(CorrectiveAction.BLOCK);
            assertThat(active.reasonCode()).isEqualTo(DefaultDecisionEngine.REASON_THRESHOLD_BLOCK);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private static CommandEvent humanEvent() {
        // A benign, non-tamper human command so neither the tamper override nor agent containment
        // fires, isolating the learning clamp under test.
        return new CommandEvent(
                "evt-1",
                Actor.human("alice"),
                null,
                "ls -la",
                "/home/alice",
                null,
                Map.of(),
                1_000L,
                InputOrigin.TYPED,
                SignalSource.HOOK,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    private static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config(double askThreshold, double blockThreshold) {
        return new ThresholdConfiguration(
                1,
                askThreshold,
                blockThreshold,
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15,
                200,
                5_000L,
                15_000L,
                1_200L,
                1_000L,
                "admin",
                1_000L);
    }
}
