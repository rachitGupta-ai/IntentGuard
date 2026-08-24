package com.intentguard.profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.InputOrigin;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.scoring.ProfileSnapshot;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Feature: intentguard-semantic-firewall, Property 7: Profiles update only on allowed events.
 *
 * <p>For any sequence of scored Command_Events, a user's Behavioral_Profile is updated for exactly
 * those events that were allowed, and is left unchanged for events that resulted in ask or block
 * (Validates: Requirements 3.2).
 *
 * <p>The property feeds a generated sequence of {@code (CommandEvent, CorrectiveAction)} pairs
 * through {@link BehavioralProfileManager#recordEvent} (the policy-aware entry point) backed by an
 * in-memory {@link BehavioralProfileRepository} fake (no live MongoDB, same pattern as
 * {@code BehavioralProfileManagerTest}). It asserts two things:
 *
 * <ol>
 *   <li><b>Unchanged on ask/block:</b> capturing the profile snapshot immediately before and after
 *       each non-{@code ALLOW} event shows the profile (event count, vocabulary, sequencing,
 *       typed-vs-pasted ratios, context associations) is completely unchanged, while an
 *       {@code ALLOW} event increments the event count by exactly one.</li>
 *   <li><b>Exactly the allowed events:</b> after the whole sequence, the persisted profile's
 *       {@code eventCount} equals the number of {@code ALLOW} events in the sequence.</li>
 * </ol>
 */
class ProfileUpdatePolicyProperties {

    private static final long NOW = 1_700_000_000_000L;
    private static final String USER = "alice";
    // Large enough that the profile stays in the LEARNING state throughout; this property is about
    // the update policy, not the learning-state boundary, so the exact value is immaterial.
    private static final int LEARNING_MIN_EVENTS = 1_000;

    @Property(tries = 200)
    void profilesUpdateOnlyOnAllowedEvents(@ForAll("scoredSequences") @Size(min = 1, max = 25) List<Step> steps) {
        InMemoryProfileRepository repository = new InMemoryProfileRepository();
        BehavioralProfileManager manager = new BehavioralProfileManager(repository);
        manager.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        long expectedAllowed = 0;
        long timestamp = NOW;

        for (Step step : steps) {
            CommandEvent event = event(step.commandText(), step.origin(), timestamp);
            timestamp += 1_000L; // strictly increasing timestamps, like a real command stream

            Snapshot before = capture(manager);
            manager.recordEvent(event, step.action(), LEARNING_MIN_EVENTS);
            Snapshot after = capture(manager);

            if (step.action() == CorrectiveAction.ALLOW) {
                expectedAllowed++;
                // An allowed event updates the profile: the event count advances by exactly one.
                assertThat(after.eventCount())
                        .as("an ALLOW event must increment the profile event count by one")
                        .isEqualTo(before.eventCount() + 1);
            } else {
                // Req 3.2: an ask/block event leaves the profile entirely unchanged.
                assertThat(after)
                        .as("a %s event must leave the profile unchanged", step.action())
                        .isEqualTo(before);
            }
        }

        // The profile was updated for exactly the allowed events and no others.
        long finalCount = capture(manager).eventCount();
        assertThat(finalCount)
                .as("final profile event count must equal the number of ALLOW events")
                .isEqualTo(expectedAllowed);
    }

    // --- generators ----------------------------------------------------------------------------

    @Provide
    Arbitrary<List<Step>> scoredSequences() {
        Arbitrary<String> commands = Arbitraries.of(
                "git commit -m x",
                "git push",
                "git status",
                "kubectl apply -f x",
                "curl https://example.com",
                "ls -la",
                "rm -rf /tmp/x",
                "npm install",
                "docker run img",
                "sudo systemctl restart");
        Arbitrary<InputOrigin> origins = Arbitraries.of(InputOrigin.TYPED, InputOrigin.PASTED, InputOrigin.UNKNOWN);
        Arbitrary<CorrectiveAction> actions = Arbitraries.of(
                CorrectiveAction.ALLOW, CorrectiveAction.ASK, CorrectiveAction.BLOCK);
        Arbitrary<Step> step = Combinators.combine(commands, origins, actions).as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(25);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static Snapshot capture(BehavioralProfileManager manager) {
        ProfileSnapshot s = manager.snapshotForUser(USER);
        return new Snapshot(
                s.eventCount(),
                new HashMap<>(s.vocabulary()),
                new HashMap<>(s.sequenceStats()),
                new HashMap<>(s.typedPastedRatioByCategory()),
                new HashMap<>(s.contextAssociations()));
    }

    private static CommandEvent event(String command, InputOrigin origin, long timestamp) {
        return new CommandEvent(
                "evt-" + command.hashCode() + "-" + timestamp,
                Actor.human(USER),
                null,
                command,
                "/home/alice/repo",
                "repo",
                Map.of(),
                timestamp,
                origin,
                null,
                null,
                null);
    }

    /** A generated scoring step: a command with an input origin and the Corrective_Action applied. */
    record Step(String commandText, InputOrigin origin, CorrectiveAction action) {
    }

    /**
     * An immutable copy of the profile fields relevant to the update policy, so before/after
     * comparisons are meaningful (the manager's snapshot builder already returns immutable copies,
     * but capturing into plain maps keeps equality checks explicit and independent).
     */
    record Snapshot(
            long eventCount,
            Map<String, Integer> vocabulary,
            Map<String, Integer> sequenceStats,
            Map<String, Double> typedPastedRatio,
            Map<String, List<String>> contextAssociations) {
    }

    /**
     * In-memory {@link BehavioralProfileRepository} keyed by userId, mirroring the fake used in
     * {@code BehavioralProfileManagerTest}: it extends the real repository so the manager sees its
     * production type, and overrides both persistence methods so the Mongo collection is never
     * touched.
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
