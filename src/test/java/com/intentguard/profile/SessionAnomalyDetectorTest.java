package com.intentguard.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link SessionAnomalyDetector} backed by an in-memory
 * {@link AuditHistoryRepository} (no live MongoDB). Cover: a sustained high-deviation sequence
 * raises exactly one alert carrying the Behavioral_Deviation evidence and persists a
 * {@code SESSION_ANOMALY} record with that evidence (Req 10.1, 10.2, 10.3); a normal low-deviation
 * sequence raises no alert; and detection is deterministic across repeated runs.
 */
class SessionAnomalyDetectorTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String USER = "alice";
    private static final double THRESHOLD = 0.6;
    private static final int WINDOW = 3;

    private InMemoryAuditRepository repository;
    private SessionAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditRepository();
        detector = new SessionAnomalyDetector(repository, THRESHOLD, WINDOW);
    }

    // --- Anomalous sequence raises exactly one alert with evidence (Req 10.1, 10.2, 10.3) ------

    @Test
    void sustainedHighDeviationSequenceRaisesExactlyOneAlertWithEvidence() {
        List<Double> deviations = List.of(0.8, 0.85, 0.9);

        List<SessionAnomalyAlert> alerts = detector.observeSequence(USER, deviations, NOW);

        assertThat(alerts).hasSize(1);
        SessionAnomalyAlert alert = alerts.get(0);
        assertThat(alert.userId()).isEqualTo(USER);
        assertThat(alert.threshold()).isEqualTo(THRESHOLD);
        assertThat(alert.evidenceDeviations()).containsExactly(0.8, 0.85, 0.9);
        assertThat(alert.meanDeviation()).isCloseTo(0.85, within(1e-9));
        assertThat(alert.message()).contains(USER);
    }

    @Test
    void anomalyPersistsSessionAnomalyRecordWithDeviationEvidence() {
        detector.observeSequence(USER, List.of(0.8, 0.85, 0.9), NOW);

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        AuditHistoryDocument record = records.get(0);
        assertThat(record.getRecordType()).isEqualTo("SESSION_ANOMALY");
        assertThat(record.getUserId()).isEqualTo(USER);
        assertThat(record.getTimestamp()).isEqualTo(NOW);
        assertThat(record.getDivergenceScore()).isCloseTo(0.85, within(1e-9));
        // Evidence deviations are embedded as BEHAVIORAL_DEVIATION component scores.
        assertThat(record.getComponents()).hasSize(3);
        assertThat(record.getComponents())
                .allSatisfy(c -> assertThat(c.getId()).isEqualTo("BEHAVIORAL_DEVIATION"));
        assertThat(record.getComponents())
                .extracting(c -> c.getScore())
                .containsExactly(0.8, 0.85, 0.9);
        assertThat(record.getExplanation()).isNotBlank();
    }

    @Test
    void raisedAlertIsExposedForControlTower() {
        detector.observeSequence(USER, List.of(0.8, 0.85, 0.9), NOW);

        assertThat(detector.raisedAlerts()).hasSize(1);
        Optional<SessionAnomalyAlert> last = detector.lastAlertFor(USER);
        assertThat(last).isPresent();
        assertThat(last.get().evidenceDeviations()).containsExactly(0.8, 0.85, 0.9);
    }

    // --- Normal sequence raises no alert -------------------------------------------------------

    @Test
    void normalLowDeviationSequenceRaisesNoAlert() {
        List<SessionAnomalyAlert> alerts =
                detector.observeSequence(USER, List.of(0.05, 0.1, 0.12, 0.08, 0.15), NOW);

        assertThat(alerts).isEmpty();
        assertThat(repository.findAll()).isEmpty();
        assertThat(detector.raisedAlerts()).isEmpty();
        assertThat(detector.lastAlertFor(USER)).isEmpty();
    }

    @Test
    void isolatedHighDeviationBelowSustainedThresholdRaisesNoAlert() {
        // A single spike surrounded by normal behavior keeps the window mean below the threshold.
        List<SessionAnomalyAlert> alerts =
                detector.observeSequence(USER, List.of(0.1, 0.95, 0.1), NOW);

        assertThat(alerts).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void partialWindowNeverRaisesAlert() {
        // Fewer events than the window size: no full window, so no detection yet.
        assertThat(detector.observe(USER, 0.99, NOW)).isEmpty();
        assertThat(detector.observe(USER, 0.99, NOW)).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    // --- Window resets after firing: a longer sustained run fires again ------------------------

    @Test
    void windowResetsAfterAlertSoASecondFullWindowFiresAgain() {
        List<SessionAnomalyAlert> alerts =
                detector.observeSequence(USER, List.of(0.8, 0.8, 0.8, 0.8, 0.8, 0.8), NOW);

        assertThat(alerts).hasSize(2);
        assertThat(repository.findAll()).hasSize(2);
    }

    // --- Determinism ---------------------------------------------------------------------------

    @Test
    void detectionIsDeterministicAcrossRepeatedRuns() {
        List<Double> deviations = List.of(0.2, 0.8, 0.85, 0.9, 0.1, 0.05);

        SessionAnomalyDetector detectorA = new SessionAnomalyDetector(new InMemoryAuditRepository(), THRESHOLD, WINDOW);
        SessionAnomalyDetector detectorB = new SessionAnomalyDetector(new InMemoryAuditRepository(), THRESHOLD, WINDOW);

        List<SessionAnomalyAlert> alertsA = detectorA.observeSequence(USER, deviations, NOW);
        List<SessionAnomalyAlert> alertsB = detectorB.observeSequence(USER, deviations, NOW);

        assertThat(alertsA).usingRecursiveComparison().isEqualTo(alertsB);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * In-memory {@link AuditHistoryRepository} that records saved documents without touching Mongo.
     * Extends the real repository so the detector sees its production type; {@code save}/{@code
     * findAll} are overridden so the Mongo collection is never used.
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
