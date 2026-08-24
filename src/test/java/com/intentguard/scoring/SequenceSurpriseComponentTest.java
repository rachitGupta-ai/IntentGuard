package com.intentguard.scoring;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;

/** Unit tests for {@link SequenceSurpriseComponent}. */
class SequenceSurpriseComponentTest {

    @Test
    void idIsSequenceSurprise() {
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.empty());
        assertThat(component.id()).isEqualTo(ComponentId.SEQUENCE_SURPRISE);
    }

    @Test
    void emptyProfileYieldsMaximalSurprise() {
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.empty());
        ComponentResult result = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status")));
        assertThat(result.score().getAsDouble()).isEqualTo(1.0);
    }

    @Test
    void unseenCommandInPopulatedProfileScoresHigherThanFrequentCommand() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300, "ls", 200))
                .build();
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.fixed(profile));

        double frequent = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status")))
                .score().getAsDouble();
        double unseen = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("curl https://x")))
                .score().getAsDouble();

        assertThat(unseen).isGreaterThan(frequent);
        assertThat(unseen).isEqualTo(1.0);
    }

    @Test
    void knownBigramTransitionIsLessSurprisingThanUnseenTransition() {
        // Profile has seen "git commit>git push" many times, with "git commit" as last command.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300))
                .sequenceStats(Map.of("git commit>git push", 120, "git commit>git status", 30))
                .lastCommandToken("git commit")
                .build();
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.fixed(profile));

        double expectedTransition = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git push origin main")))
                .score().getAsDouble();
        double unexpectedTransition = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git rebase -i")))
                .score().getAsDouble();

        assertThat(expectedTransition).isLessThan(unexpectedTransition);
        assertThat(unexpectedTransition).isEqualTo(1.0);
    }

    @Test
    void scoreIsAlwaysInUnitIntervalAcrossVariedInputs() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 10, "ls", 5))
                .sequenceStats(Map.of("ls>git status", 3))
                .lastCommandToken("ls")
                .build();
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.fixed(profile));

        String[] commands = {"git status", "ls -la", "curl https://x", "", "sudo rm -rf /", "kubectl apply"};
        for (String cmd : commands) {
            double score = component.score(ScoringTestSupport.context(
                    ScoringTestSupport.event(cmd, "/home/alice", null, InputOrigin.TYPED))).score().getAsDouble();
            assertThat(score).as("score for '%s'", cmd).isBetween(0.0, 1.0);
        }
    }

    @Test
    void isDeterministic() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 42))
                .build();
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.fixed(profile));

        double a = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status"))).score().getAsDouble();
        double b = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status"))).score().getAsDouble();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void appliesConfiguredWeight() {
        SequenceSurpriseComponent component = new SequenceSurpriseComponent(ProfileSnapshotProvider.empty());
        ComponentResult result = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status")));
        assertThat(result.weight()).isEqualTo(0.25);
    }
}
