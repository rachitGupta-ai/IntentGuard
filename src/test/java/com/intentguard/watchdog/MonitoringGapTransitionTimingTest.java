package com.intentguard.watchdog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.ComponentId;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Transition-timing unit tests for {@link MonitoringGapWatchdog} (task 12.3, Req 1.4 & 1.5).
 *
 * <p>These tests are dedicated to the exact timing of the monitoring-gap state machine. They drive
 * the watchdog with a controllable {@link Clock} and an in-memory {@link AuditHistoryRepository}
 * (no live MongoDB) and pin down:
 * <ul>
 *   <li>the raise boundary just-before / exactly-at / just-after the timeout (Req 1.4);</li>
 *   <li>that the alert is raised synchronously by {@link MonitoringGapWatchdog#checkForGap(long)}
 *       the moment the timeout is exceeded — a scheduler polling below the 2 s detection budget
 *       therefore guarantees the alert within 2 s of the gap opening (Req 1.4);</li>
 *   <li>the clear timing on liveness restoration (Req 1.5);</li>
 *   <li>the full raise -&gt; clear -&gt; raise-again sequence and its exactly-once semantics.</li>
 * </ul>
 *
 * <p>These complement {@link MonitoringGapWatchdogTest}: that class establishes the base
 * record/alert behaviour, while this class exercises the timing edges around the timeout and the
 * repeated transition cycle.
 */
class MonitoringGapTransitionTimingTest {

    /** Fixed epoch-millis base so every timestamp offset is easy to reason about. */
    private static final long BASE = 1_700_000_000_000L;

    /** The default monitoring-gap timeout the watchdog uses when no active config is present. */
    private static final long TIMEOUT_MS = 5000L;

    private InMemoryAuditRepository repository;
    private ThresholdConfigurationService thresholdConfigService;
    private MonitoringGapWatchdog watchdog;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditRepository();
        thresholdConfigService = mock(ThresholdConfigurationService.class);
        // No active config -> the watchdog falls back to its default 5s timeout.
        when(thresholdConfigService.getActiveConfig()).thenReturn(Optional.empty());
        watchdog = new MonitoringGapWatchdog(repository, thresholdConfigService);
        // Seed the last-liveness marker at BASE so all elapsed times are measured from BASE.
        watchdog.setClock(Clock.fixed(Instant.ofEpochMilli(BASE), ZoneOffset.UTC));
    }

    // --- Raise boundary: just-before / at / just-after the timeout (Req 1.4) --------------------

    @Test
    void justBeforeTimeoutNoGapNoAlertNoRecord() {
        // elapsed = TIMEOUT_MS - 1 (< timeout) -> still healthy.
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(BASE + TIMEOUT_MS - 1);

        assertThat(alert).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(watchdog.currentAlert()).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void exactlyAtTimeoutIsNotYetAGap() {
        // The implementation uses a strict `elapsed > timeout`, so elapsed == timeout is NOT a gap.
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(BASE + TIMEOUT_MS);

        assertThat(alert).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void oneMillisPastTimeoutRaisesTheAlertSynchronously() {
        long detectedAt = BASE + TIMEOUT_MS + 1;

        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(detectedAt);

        // The alert is present in the return value AND active immediately after the call returns:
        // detection is synchronous, so a sub-2s poll interval guarantees the 2s budget (Req 1.4).
        assertThat(alert).isPresent();
        assertThat(watchdog.isAlertActive()).isTrue();
        assertThat(watchdog.currentAlert()).contains(alert.orElseThrow());

        MonitoringGapAlert raised = alert.orElseThrow();
        assertThat(raised.isHighRisk()).isTrue();
        assertThat(raised.detectedAtMillis()).isEqualTo(detectedAt);
        assertThat(raised.lastLivenessMillis()).isEqualTo(BASE);
        assertThat(raised.gapMillis()).isEqualTo(TIMEOUT_MS + 1);
        assertThat(raised.timeoutMillis()).isEqualTo(TIMEOUT_MS);

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_GAP);
        assertThat(records.get(0).getTimestamp()).isEqualTo(detectedAt);
    }

    @Test
    void recordedLivenessAdvancesTheRaiseBoundary() {
        // A liveness signal after BASE pushes the last-liveness marker forward, so a check that
        // would have tripped relative to BASE no longer does relative to the newer marker.
        long liveness = BASE + 3000;
        watchdog.recordLiveness(liveness);

        // Relative to BASE this is 5001 ms (would be a gap) but relative to the new marker it is
        // only 2001 ms (< timeout) -> no gap.
        assertThat(watchdog.checkForGap(BASE + TIMEOUT_MS + 1)).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();

        // Exactly one millisecond past the timeout measured from the new marker -> gap.
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(liveness + TIMEOUT_MS + 1);
        assertThat(alert).isPresent();
        assertThat(alert.orElseThrow().lastLivenessMillis()).isEqualTo(liveness);
    }

    // --- Clear timing on restoration (Req 1.5) --------------------------------------------------

    @Test
    void alertClearsExactlyWhenLivenessIsRestored() {
        watchdog.checkForGap(BASE + TIMEOUT_MS + 1);
        assertThat(watchdog.isAlertActive()).isTrue();

        long restoredAt = BASE + TIMEOUT_MS + 1500;
        watchdog.recordLiveness(restoredAt);

        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(watchdog.currentAlert()).isEmpty();
        assertThat(watchdog.lastLivenessMillis()).isEqualTo(restoredAt);

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(2);
        AuditHistoryDocument resumed = records.get(1);
        assertThat(resumed.getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_RESUMED);
        assertThat(resumed.getTimestamp()).isEqualTo(restoredAt);
        assertThat(resumed.getDivergenceScore()).isEqualTo(0.0);
    }

    @Test
    void staleLivenessDuringAGapStillClearsTheAlertButDoesNotRewindTheMarker() {
        watchdog.checkForGap(BASE + TIMEOUT_MS + 1);
        assertThat(watchdog.isAlertActive()).isTrue();

        // A liveness signal whose timestamp is older than the current marker (e.g. an out-of-order
        // delivery). It must not rewind the marker, but it still represents restored liveness and
        // clears the alert (Req 1.5).
        watchdog.recordLiveness(BASE - 1000);

        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(watchdog.lastLivenessMillis()).isEqualTo(BASE);
        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(2);
        assertThat(records.get(1).getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_RESUMED);
    }

    // --- Full raise -> clear -> raise-again cycle -----------------------------------------------

    @Test
    void raiseClearRaiseAgainProducesOrderedRecordsAndReArmsTheAlert() {
        // 1) Raise.
        long firstGapAt = BASE + TIMEOUT_MS + 1;
        assertThat(watchdog.checkForGap(firstGapAt)).isPresent();
        // Repeated checks while still blind must not re-raise or duplicate records.
        assertThat(watchdog.checkForGap(firstGapAt + 100)).isEmpty();
        assertThat(watchdog.checkForGap(firstGapAt + 1000)).isEmpty();
        assertThat(watchdog.isAlertActive()).isTrue();

        // 2) Clear.
        long restoredAt = firstGapAt + 2000;
        watchdog.recordLiveness(restoredAt);
        assertThat(watchdog.isAlertActive()).isFalse();

        // Just after restoration, still within the timeout of the new marker -> no re-raise.
        assertThat(watchdog.checkForGap(restoredAt + TIMEOUT_MS)).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();

        // 3) Raise again once the fresh timeout is exceeded.
        long secondGapAt = restoredAt + TIMEOUT_MS + 1;
        Optional<MonitoringGapAlert> second = watchdog.checkForGap(secondGapAt);
        assertThat(second).isPresent();
        assertThat(second.orElseThrow().lastLivenessMillis()).isEqualTo(restoredAt);
        assertThat(second.orElseThrow().detectedAtMillis()).isEqualTo(secondGapAt);
        assertThat(watchdog.isAlertActive()).isTrue();

        // Records, in order: GAP, RESUMED, GAP.
        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_GAP);
        assertThat(records.get(1).getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_RESUMED);
        assertThat(records.get(2).getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_GAP);
        assertThat(records.get(0).getTimestamp()).isEqualTo(firstGapAt);
        assertThat(records.get(1).getTimestamp()).isEqualTo(restoredAt);
        assertThat(records.get(2).getTimestamp()).isEqualTo(secondGapAt);
    }

    // --- Raise boundary honours a configured (non-default) timeout ------------------------------

    @Test
    void configuredTimeoutShiftsTheRaiseBoundary() {
        long configuredTimeout = 2000L;
        when(thresholdConfigService.getActiveConfig())
                .thenReturn(Optional.of(configWithTimeout(configuredTimeout)));

        assertThat(watchdog.timeoutMillis()).isEqualTo(configuredTimeout);

        // Exactly at the configured timeout -> not yet a gap (strict >).
        assertThat(watchdog.checkForGap(BASE + configuredTimeout)).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();

        // One millisecond past the configured timeout -> gap raised.
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(BASE + configuredTimeout + 1);
        assertThat(alert).isPresent();
        assertThat(alert.orElseThrow().timeoutMillis()).isEqualTo(configuredTimeout);
        assertThat(watchdog.isAlertActive()).isTrue();
    }

    // --- helpers --------------------------------------------------------------------------------

    private static ThresholdConfiguration configWithTimeout(long monitoringGapTimeoutMs) {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        return new ThresholdConfiguration(
                1, 0.4, 0.7, weights, 0.15, 200, monitoringGapTimeoutMs, 15000, 1200, 1000, "admin", BASE);
    }

    /**
     * In-memory {@link AuditHistoryRepository} that records saved documents without touching Mongo.
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
