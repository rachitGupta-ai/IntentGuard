package com.intentguard.profile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link SessionAnomalyDetector}.
 *
 * <p>Feature: intentguard-semantic-firewall, Property 15: Session anomalies raise a recorded alert
 * with evidence.
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.3.
 *
 * <p>For any sequence of Command_Events whose deviation from the user's Behavioral_Profile exceeds
 * the configured deviation threshold, a session-anomaly alert is raised that includes the
 * Behavioral_Deviation evidence and is persisted to the Audit_History. The complementary facet
 * asserts that a window whose sustained deviation stays below the threshold raises no alert and
 * persists nothing.
 */
class SessionAnomalyDetectorPropertyTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String USER = "session-user";

    /**
     * A full window of Behavioral_Deviation values that are all >= the configured threshold (so the
     * window mean is guaranteed >= threshold) MUST raise exactly one alert carrying that evidence,
     * and MUST persist a matching SESSION_ANOMALY record to the Audit_History.
     */
    @Property(tries = 200)
    void sustainedHighDeviationWindowRaisesRecordedAlertWithEvidence(
            @ForAll("highDeviationScenarios") Scenario scenario) {
        InMemoryAuditRepository repository = new InMemoryAuditRepository();
        SessionAnomalyDetector detector =
                new SessionAnomalyDetector(repository, scenario.threshold, scenario.windowSize);

        List<SessionAnomalyAlert> alerts = detector.observeSequence(USER, scenario.deviations, NOW);

        double expectedMean = mean(scenario.deviations);

        // Exactly one alert is raised for the single sustained window.
        assertThat(alerts).hasSize(1);
        SessionAnomalyAlert alert = alerts.get(0);
        assertThat(alert.userId()).isEqualTo(USER);
        assertThat(alert.threshold()).isEqualTo(scenario.threshold);
        assertThat(alert.timestamp()).isEqualTo(NOW);

        // The alert includes the Behavioral_Deviation evidence.
        assertThat(alert.evidenceDeviations()).containsExactlyElementsOf(scenario.deviations);
        assertThat(alert.meanDeviation()).isCloseTo(expectedMean, within(1e-9));

        // A SESSION_ANOMALY record carrying the evidence was persisted to the Audit_History.
        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        AuditHistoryDocument record = records.get(0);
        assertThat(record.getRecordType()).isEqualTo("SESSION_ANOMALY");
        assertThat(record.getUserId()).isEqualTo(USER);
        assertThat(record.getDivergenceScore()).isCloseTo(expectedMean, within(1e-9));
        assertThat(record.getComponents()).hasSize(scenario.windowSize);
        assertThat(record.getComponents())
                .allSatisfy(c -> assertThat(c.getId()).isEqualTo("BEHAVIORAL_DEVIATION"));
        assertThat(record.getComponents())
                .extracting(c -> c.getScore())
                .containsExactlyElementsOf(scenario.deviations);

        // Also exposed for the Control_Tower.
        assertThat(detector.raisedAlerts()).hasSize(1);
        assertThat(detector.lastAlertFor(USER)).isPresent();
    }

    /**
     * Complementary facet: a full window whose values are all well below the threshold (mean <
     * threshold) MUST raise no alert and persist nothing.
     */
    @Property(tries = 200)
    void lowDeviationWindowRaisesNoAlertAndPersistsNothing(
            @ForAll("lowDeviationScenarios") Scenario scenario) {
        InMemoryAuditRepository repository = new InMemoryAuditRepository();
        SessionAnomalyDetector detector =
                new SessionAnomalyDetector(repository, scenario.threshold, scenario.windowSize);

        List<SessionAnomalyAlert> alerts = detector.observeSequence(USER, scenario.deviations, NOW);

        assertThat(mean(scenario.deviations)).isLessThan(scenario.threshold);
        assertThat(alerts).isEmpty();
        assertThat(repository.findAll()).isEmpty();
        assertThat(detector.raisedAlerts()).isEmpty();
        assertThat(detector.lastAlertFor(USER)).isEmpty();
    }

    // --- generators ----------------------------------------------------------------------------

    // Deviations and thresholds are generated as exact hundredths (integer percent / 100.0) so
    // every value is representable without rounding and the ordering relative to the threshold is
    // exact — a deviation drawn at or above the threshold percent is guaranteed >= the threshold.

    @Provide
    Arbitrary<Scenario> highDeviationScenarios() {
        Arbitrary<Integer> thresholdPercents = Arbitraries.integers().between(10, 90);
        Arbitrary<Integer> windowSizes = Arbitraries.integers().between(1, 8);
        return Combinators.combine(thresholdPercents, windowSizes).flatAs((thresholdPct, windowSize) -> {
            double threshold = thresholdPct / 100.0;
            // Every deviation percent is in [thresholdPct + 1, 100], so each deviation is strictly
            // above the threshold (by at least 0.01, well beyond floating-point noise) and <= 1.0.
            // The window mean is therefore > threshold and an alert MUST fire once the full window
            // is observed.
            Arbitrary<List<Double>> window = Arbitraries.integers()
                    .between(thresholdPct + 1, 100)
                    .map(pct -> pct / 100.0)
                    .list()
                    .ofSize(windowSize);
            return window.map(deviations -> new Scenario(threshold, windowSize, deviations));
        });
    }

    @Provide
    Arbitrary<Scenario> lowDeviationScenarios() {
        Arbitrary<Integer> thresholdPercents = Arbitraries.integers().between(20, 95);
        Arbitrary<Integer> windowSizes = Arbitraries.integers().between(1, 8);
        return Combinators.combine(thresholdPercents, windowSizes).flatAs((thresholdPct, windowSize) -> {
            double threshold = thresholdPct / 100.0;
            // Every deviation percent is in [0, thresholdPct - 1], so each deviation is strictly
            // below the threshold and the window mean stays below it: no alert can fire.
            Arbitrary<List<Double>> window = Arbitraries.integers()
                    .between(0, thresholdPct - 1)
                    .map(pct -> pct / 100.0)
                    .list()
                    .ofSize(windowSize);
            return window.map(deviations -> new Scenario(threshold, windowSize, deviations));
        });
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** A generated detection scenario: configured threshold/window plus one full window of deviations. */
    private static final class Scenario {
        private final double threshold;
        private final int windowSize;
        private final List<Double> deviations;

        Scenario(double threshold, int windowSize, List<Double> deviations) {
            this.threshold = threshold;
            this.windowSize = windowSize;
            this.deviations = deviations;
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * In-memory {@link AuditHistoryRepository} that records saved documents without touching Mongo.
     * Mirrors the fake used by {@code SessionAnomalyDetectorTest}: extends the real repository so the
     * detector sees its production type, overriding {@code save}/{@code findAll} so the Mongo
     * collection is never used.
     */
    private static final class InMemoryAuditRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> store = new ArrayList<>();

        InMemoryAuditRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            store.add(record);
        }

        @Override
        public List<AuditHistoryDocument> findAll() {
            return new ArrayList<>(store);
        }
    }
}
