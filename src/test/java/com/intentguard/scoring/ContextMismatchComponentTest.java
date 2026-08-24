package com.intentguard.scoring;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;

/** Unit tests for {@link ContextMismatchComponent}. */
class ContextMismatchComponentTest {

    @Test
    void idIsContextMismatch() {
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.empty());
        assertThat(component.id()).isEqualTo(ComponentId.CONTEXT_MISMATCH);
    }

    @Test
    void consistentCategoryInLearnedContextScoresZero() {
        // vcs has been seen in repoDir; a git command in a repo is consistent.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .contextAssociations(Map.of("vcs", List.of("repoDir")))
                .build();
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.fixed(profile));

        CommandEvent event = ScoringTestSupport.event("git status", "/home/alice/proj", "proj", InputOrigin.TYPED);
        double score = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void inconsistentContextScoresMaximal() {
        // network has only ever appeared in repoDir; running curl from home is inconsistent.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .contextAssociations(Map.of("network", List.of("repoDir")))
                .build();
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.fixed(profile));

        CommandEvent event = ScoringTestSupport.event("curl https://x", "/home/alice", null, InputOrigin.TYPED);
        double score = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void unknownCategoryInNonEmptyProfileScoresModerate() {
        // Profile knows vcs contexts but nothing about network.
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .contextAssociations(Map.of("vcs", List.of("repoDir")))
                .build();
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.fixed(profile));

        CommandEvent event = ScoringTestSupport.event("curl https://x", "/home/alice", null, InputOrigin.TYPED);
        double score = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        assertThat(score).isEqualTo(ContextMismatchComponent.UNKNOWN_CATEGORY_SCORE);
    }

    @Test
    void emptyProfileScoresZero(){
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.empty());
        CommandEvent event = ScoringTestSupport.event("curl https://x", "/etc", null, InputOrigin.TYPED);
        double score = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void contextTagsClassifyRepoHomeSystemAndTmp() {
        assertThat(ContextMismatchComponent.contextTags(
                ScoringTestSupport.event("git status", "/home/alice/proj", "proj", InputOrigin.TYPED)))
                .containsExactly("repoDir");
        assertThat(ContextMismatchComponent.contextTags(
                ScoringTestSupport.event("ls", "/home/alice", null, InputOrigin.TYPED)))
                .containsExactly("home");
        assertThat(ContextMismatchComponent.contextTags(
                ScoringTestSupport.event("ls", "/etc", null, InputOrigin.TYPED)))
                .containsExactly("system");
        assertThat(ContextMismatchComponent.contextTags(
                ScoringTestSupport.event("ls", "/tmp", null, InputOrigin.TYPED)))
                .containsExactly("tmp");
    }

    @Test
    void scoreIsAlwaysInUnitIntervalAcrossVariedInputs() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .contextAssociations(Map.of("vcs", List.of("repoDir"), "network", List.of("home")))
                .build();
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.fixed(profile));

        String[][] cases = {
                {"git status", "/home/alice/proj", "proj"},
                {"curl https://x", "/home/alice", null},
                {"kubectl apply", "/tmp", null},
                {"", "/etc", null},
                {"rm -rf /", "/var", null},
        };
        for (String[] c : cases) {
            CommandEvent event = ScoringTestSupport.event(c[0], c[1], c[2], InputOrigin.TYPED);
            double score = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
            assertThat(score).as("score for %s", c[0]).isBetween(0.0, 1.0);
        }
    }

    @Test
    void isDeterministic() {
        ProfileSnapshot profile = ProfileSnapshot.builder()
                .contextAssociations(Map.of("vcs", List.of("repoDir")))
                .build();
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.fixed(profile));
        CommandEvent event = ScoringTestSupport.event("git status", "/home/alice/proj", "proj", InputOrigin.TYPED);

        double a = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        double b = component.score(ScoringTestSupport.context(event)).score().getAsDouble();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void appliesConfiguredWeight() {
        ContextMismatchComponent component = new ContextMismatchComponent(ProfileSnapshotProvider.empty());
        ComponentResult result = component.score(ScoringTestSupport.context(ScoringTestSupport.typed("git status")));
        assertThat(result.weight()).isEqualTo(0.20);
    }
}
