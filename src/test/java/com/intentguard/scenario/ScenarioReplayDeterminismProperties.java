package com.intentguard.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.ScenarioBaselineDocument;
import com.intentguard.persistence.ScenarioBaselineRepository;
import com.intentguard.persistence.ThresholdConfigRepository;
import com.intentguard.scenario.DemoScenarios.ScenarioDefinition;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Feature: intentguard-semantic-firewall, Property 20: Scenario replays from a fixed baseline are
 * deterministic.
 *
 * <p>For any scripted demo scenario initialized from its fixed baseline Behavioral_Profile and
 * Threshold_Configuration (with the LLM stubbed deterministically), replaying the scenario more than
 * once produces an identical sequence of Corrective_Actions (Validates: Requirements 16.1, 16.2).
 *
 * <p>Each replay is performed against a <b>fresh</b> in-memory Behavioral_Profile repository, a fresh
 * scenario-baseline repository, and a fresh {@link ThresholdConfigurationService}, so the harness's
 * "reset to the frozen seed" path (Req 16.1) is exercised anew on every replay. Determinism (Req
 * 16.2) is then asserted across {@code N} independent replays (with {@code N} varied in {@code
 * [2,5]}) over an arbitrary choice among the four demo scenarios: not only must the sequence of
 * Corrective_Actions be identical, but the reason codes, Divergence_Scores, and explanations must
 * match too, giving a stronger determinism guarantee.
 */
class ScenarioReplayDeterminismProperties {

    // RANDOMIZED generation forces the full 200 iterations by sampling: the {scenarioIndex,
    // replayCount} space is small (4x4), which jqwik would otherwise exhaust in only 16 tries.
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void replayingTheSameScenarioFromItsFixedBaselineIsDeterministic(
            @ForAll("scenarioIndex") int scenarioIndex,
            @ForAll @IntRange(min = 2, max = 5) int replayCount) {

        // Replay the SAME scenario N independent times, each from a fresh set of repositories and a
        // fresh threshold service, so every replay resets to the frozen seed baseline (Req 16.1).
        List<ScenarioReplayReport> reports = new ArrayList<>(replayCount);
        for (int i = 0; i < replayCount; i++) {
            reports.add(freshReplay(scenarioIndex));
        }

        ScenarioReplayReport baseline = reports.get(0);
        List<CorrectiveAction> baselineActions = baseline.actions();

        for (int i = 1; i < reports.size(); i++) {
            ScenarioReplayReport replay = reports.get(i);

            // The scenario id and applied thresholds are the same frozen configuration every time.
            assertThat(replay.scenarioId()).isEqualTo(baseline.scenarioId());

            // Core Property 20 guarantee: identical sequence of Corrective_Actions.
            assertThat(replay.actions())
                    .as("scenario '%s' replay #%s Corrective_Actions", baseline.scenarioId(), i)
                    .isEqualTo(baselineActions);

            // Stronger determinism: per-event reason codes, scores, and explanations also match.
            assertThat(replay.results()).hasSameSizeAs(baseline.results());
            for (int e = 0; e < baseline.results().size(); e++) {
                ScenarioReplayResult expected = baseline.results().get(e);
                ScenarioReplayResult actual = replay.results().get(e);
                assertThat(actual.eventId()).isEqualTo(expected.eventId());
                assertThat(actual.action()).isEqualTo(expected.action());
                assertThat(actual.reasonCode()).isEqualTo(expected.reasonCode());
                assertThat(actual.divergenceScore()).isEqualTo(expected.divergenceScore());
                assertThat(actual.explanation()).isEqualTo(expected.explanation());
            }
        }
    }

    /**
     * Runs one full, independent replay of the demo scenario at {@code scenarioIndex} from a freshly
     * built baseline, profile repository, and threshold service. Because {@link DemoScenarios}
     * rebuilds its definitions from constants on every call, each replay starts from an identical
     * frozen seed with no shared mutable state.
     */
    private ScenarioReplayReport freshReplay(int scenarioIndex) {
        DemoScenarios scenarios = new DemoScenarios();
        ScenarioDefinition def = scenarios.all().get(scenarioIndex);

        InMemoryScenarioBaselineRepository baselineRepo = new InMemoryScenarioBaselineRepository();
        InMemoryProfileRepository profileRepo = new InMemoryProfileRepository();
        ThresholdConfigurationService thresholdService =
                new ThresholdConfigurationService(mock(ThresholdConfigRepository.class));

        baselineRepo.save(def.baseline());
        ScenarioReplayHarness harness =
                scenarios.harnessFor(def, baselineRepo, profileRepo, thresholdService);
        return harness.replay(def.baseline());
    }

    /** An index over the four demo scenarios; jqwik samples it across the configured iterations. */
    @Provide
    Arbitrary<Integer> scenarioIndex() {
        return Arbitraries.integers().between(0, new DemoScenarios().all().size() - 1);
    }

    // --- In-memory repository fakes (no live MongoDB) -----------------------------------------

    private static final class InMemoryScenarioBaselineRepository extends ScenarioBaselineRepository {
        private final Map<String, ScenarioBaselineDocument> store = new HashMap<>();

        InMemoryScenarioBaselineRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(ScenarioBaselineDocument baseline) {
            store.put(baseline.getScenarioId(), baseline);
        }

        @Override
        public Optional<ScenarioBaselineDocument> findByScenarioId(String scenarioId) {
            return Optional.ofNullable(store.get(scenarioId));
        }
    }

    private static final class InMemoryProfileRepository extends BehavioralProfileRepository {
        private final Map<String, BehavioralProfileDocument> store = new HashMap<>();

        InMemoryProfileRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(BehavioralProfileDocument profile) {
            store.put(profile.getUserId(), profile);
        }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.ofNullable(store.get(userId));
        }
    }
}
