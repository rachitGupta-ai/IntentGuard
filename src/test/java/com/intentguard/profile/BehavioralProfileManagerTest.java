package com.intentguard.profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringConfig;
import com.intentguard.domain.ScoringContext;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.scoring.ProfileSnapshot;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link BehavioralProfileManager} backed by an in-memory
 * {@link BehavioralProfileRepository} (no live MongoDB). Cover: an allowed event updates
 * vocabulary/sequence/ratio/context and increments the event count and persists (Req 3.1, 3.2);
 * ask/block events do not update the profile (Req 3.2); the learning state flips to ACTIVE at the
 * configured minimum (Req 3.3); the snapshot reflects the persisted profile and survives a
 * simulated restart (Req 3.5); and recording is deterministic.
 */
class BehavioralProfileManagerTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String USER = "alice";

    private InMemoryProfileRepository repository;
    private BehavioralProfileManager manager;

    @BeforeEach
    void setUp() {
        repository = new InMemoryProfileRepository();
        manager = new BehavioralProfileManager(repository);
        manager.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // --- Allowed event updates the profile (Req 3.1, 3.2) --------------------------------------

    @Test
    void allowedEventUpdatesVocabularyRatioContextAndIncrementsCount() {
        CommandEvent event = typed("git commit -m x", "/home/alice/repo", "repo");

        manager.recordAllowedEvent(event, 200);

        BehavioralProfileDocument profile = repository.findByUserId(USER).orElseThrow();
        assertThat(profile.getEventCount()).isEqualTo(1);
        assertThat(profile.getVocabulary()).containsEntry("git", 1);
        // vcs category typed event seeds the ratio to 1.0 (fully typed).
        assertThat(profile.getTypedPastedRatioByCategory()).containsEntry("vcs", 1.0);
        // Inside a repository under a home directory -> both tags recorded for the category.
        assertThat(profile.getContextAssociations().get("vcs"))
                .contains(BehavioralProfileManager.TAG_REPO_DIR, BehavioralProfileManager.TAG_HOME);
        assertThat(profile.getUpdatedAt()).isEqualTo(NOW);
        assertThat(profile.getTimingPatterns().getHourHistogram()).hasSize(24);
    }

    @Test
    void secondAllowedEventBuildsBigramOverNormalizedTokens() {
        manager.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 200);
        manager.recordAllowedEvent(typed("git push", "/home/alice/repo", "repo"), 200);

        BehavioralProfileDocument profile = repository.findByUserId(USER).orElseThrow();
        assertThat(profile.getEventCount()).isEqualTo(2);
        // Bigram keyed "prevToken>currToken" over normalized tokens.
        assertThat(profile.getSequenceStats()).containsEntry("git commit>git push", 1);
    }

    @Test
    void pastedEventLowersTypedPastedRatioForCategory() {
        manager.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 200);
        manager.recordAllowedEvent(pasted("git push", "/home/alice/repo", "repo"), 200);

        double ratio = repository.findByUserId(USER).orElseThrow().getTypedPastedRatioByCategory().get("vcs");
        // Started at 1.0 (typed), a subsequent pasted event nudges it below 1.0.
        assertThat(ratio).isLessThan(1.0).isGreaterThanOrEqualTo(0.0);
    }

    // --- Update policy: only allowed events update (Req 3.2) -----------------------------------

    @Test
    void askAndBlockEventsDoNotUpdateProfile() {
        manager.recordEvent(typed("git status", "/home/alice/repo", "repo"), CorrectiveAction.ASK, 200);
        manager.recordEvent(typed("rm -rf /", "/home/alice/repo", "repo"), CorrectiveAction.BLOCK, 200);

        assertThat(repository.findByUserId(USER)).isEmpty();
    }

    @Test
    void recordEventUpdatesOnlyOnAllow() {
        manager.recordEvent(typed("git status", "/home/alice/repo", "repo"), CorrectiveAction.ASK, 200);
        manager.recordEvent(typed("git commit -m x", "/home/alice/repo", "repo"), CorrectiveAction.ALLOW, 200);
        manager.recordEvent(typed("git push", "/home/alice/repo", "repo"), CorrectiveAction.BLOCK, 200);

        BehavioralProfileDocument profile = repository.findByUserId(USER).orElseThrow();
        assertThat(profile.getEventCount()).isEqualTo(1);
        assertThat(profile.getVocabulary()).containsEntry("git", 1);
    }

    // --- Learning state (Req 3.3) --------------------------------------------------------------

    @Test
    void learningStateFlipsToActiveAtConfiguredMinimum() {
        int min = 3;
        assertThat(manager.profileStateFor(USER, min)).isEqualTo(ProfileState.LEARNING);

        manager.recordAllowedEvent(typed("git commit -m a", "/home/alice/repo", "repo"), min);
        assertThat(manager.profileStateFor(USER, min)).isEqualTo(ProfileState.LEARNING);

        manager.recordAllowedEvent(typed("git commit -m b", "/home/alice/repo", "repo"), min);
        assertThat(manager.profileStateFor(USER, min)).isEqualTo(ProfileState.LEARNING);

        manager.recordAllowedEvent(typed("git commit -m c", "/home/alice/repo", "repo"), min);
        assertThat(manager.profileStateFor(USER, min)).isEqualTo(ProfileState.ACTIVE);

        // State is also stamped on the persisted profile.
        assertThat(repository.findByUserId(USER).orElseThrow().getState())
                .isEqualTo(ProfileState.ACTIVE.name());
    }

    // --- Snapshot reflects persisted profile, incl. across a restart (Req 3.5) -----------------

    @Test
    void snapshotReflectsPersistedProfile() {
        manager.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 200);
        manager.recordAllowedEvent(typed("git push", "/home/alice/repo", "repo"), 200);

        ProfileSnapshot snapshot = manager.snapshotForUser(USER);
        assertThat(snapshot.eventCount()).isEqualTo(2);
        assertThat(snapshot.vocabulary()).containsEntry("git", 2);
        assertThat(snapshot.sequenceStats()).containsEntry("git commit>git push", 1);
        // lastCommandToken is the most recent allowed token, so the next bigram can be formed.
        assertThat(snapshot.lastCommandToken()).contains("git push");
    }

    @Test
    void snapshotForUnknownUserIsEmpty() {
        ProfileSnapshot snapshot = manager.snapshotForUser("nobody");
        assertThat(snapshot.eventCount()).isZero();
        assertThat(snapshot.vocabulary()).isEmpty();
        assertThat(snapshot.lastCommandToken()).isEmpty();
    }

    @Test
    void snapshotForScoringContextIsEmptyForUnknownUserAndPresentAfterLearning() {
        ScoringContext ctx = contextFor(typed("git status", "/home/alice/repo", "repo"));
        assertThat(manager.snapshotFor(ctx)).isEmpty();

        manager.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 200);
        assertThat(manager.snapshotFor(ctx)).isPresent();
    }

    @Test
    void profilePersistsAndReloadsAcrossSimulatedRestart() {
        manager.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 3);
        manager.recordAllowedEvent(typed("kubectl apply", "/home/alice/repo", "repo"), 3);
        manager.recordAllowedEvent(typed("git push", "/home/alice/repo", "repo"), 3);

        // Simulate a restart: a fresh manager over the same (persistent) repository.
        BehavioralProfileManager restarted = new BehavioralProfileManager(repository);

        assertThat(restarted.profileStateFor(USER, 3)).isEqualTo(ProfileState.ACTIVE);
        ProfileSnapshot snapshot = restarted.snapshotForUser(USER);
        assertThat(snapshot.eventCount()).isEqualTo(3);
        assertThat(snapshot.vocabulary()).containsEntry("git", 2).containsEntry("kubectl", 1);
    }

    // --- Determinism ---------------------------------------------------------------------------

    @Test
    void recordingTheSameSequenceIsDeterministic() {
        InMemoryProfileRepository repoA = new InMemoryProfileRepository();
        InMemoryProfileRepository repoB = new InMemoryProfileRepository();
        BehavioralProfileManager managerA = fixedClockManager(repoA);
        BehavioralProfileManager managerB = fixedClockManager(repoB);

        for (BehavioralProfileManager m : new BehavioralProfileManager[] {managerA, managerB}) {
            m.recordAllowedEvent(typed("git commit -m x", "/home/alice/repo", "repo"), 200);
            m.recordAllowedEvent(pasted("curl http://x", "/home/alice", null), 200);
            m.recordAllowedEvent(typed("git push", "/home/alice/repo", "repo"), 200);
        }

        BehavioralProfileDocument a = repoA.findByUserId(USER).orElseThrow();
        BehavioralProfileDocument b = repoB.findByUserId(USER).orElseThrow();
        assertThat(a.getEventCount()).isEqualTo(b.getEventCount());
        assertThat(a.getVocabulary()).isEqualTo(b.getVocabulary());
        assertThat(a.getSequenceStats()).isEqualTo(b.getSequenceStats());
        assertThat(a.getTypedPastedRatioByCategory()).isEqualTo(b.getTypedPastedRatioByCategory());
        assertThat(a.getContextAssociations()).isEqualTo(b.getContextAssociations());
    }

    // --- helpers -------------------------------------------------------------------------------

    private BehavioralProfileManager fixedClockManager(InMemoryProfileRepository repo) {
        BehavioralProfileManager m = new BehavioralProfileManager(repo);
        m.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return m;
    }

    private static CommandEvent typed(String command, String cwd, String repo) {
        return event(command, cwd, repo, InputOrigin.TYPED);
    }

    private static CommandEvent pasted(String command, String cwd, String repo) {
        return event(command, cwd, repo, InputOrigin.PASTED);
    }

    private static CommandEvent event(String command, String cwd, String repo, InputOrigin origin) {
        return new CommandEvent(
                "evt-" + command.hashCode(),
                Actor.human(USER),
                null,
                command,
                cwd,
                repo,
                Map.of(),
                NOW,
                origin,
                null,
                null,
                null);
    }

    private static ScoringContext contextFor(CommandEvent event) {
        return new ScoringContext(event, null, null, ProfileState.LEARNING, new ScoringConfig(Map.of(), 0.0));
    }

    /**
     * In-memory {@link BehavioralProfileRepository} keyed by userId. Extends the real repository so
     * the manager sees its production type; the Mongo collection is never touched because both
     * methods are overridden. A single map instance models the persistent Datastore, so sharing it
     * with a fresh manager simulates a restart.
     */
    private static final class InMemoryProfileRepository extends BehavioralProfileRepository {
        private final Map<String, BehavioralProfileDocument> store = new HashMap<>();

        InMemoryProfileRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public void save(BehavioralProfileDocument profile) {
            store.put(profile.getUserId(), profile);
        }
    }
}
