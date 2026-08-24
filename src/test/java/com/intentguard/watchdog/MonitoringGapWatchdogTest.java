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
 * Unit tests for {@link MonitoringGapWatchdog} driven by a controllable clock and an in-memory
 * {@link AuditHistoryRepository} (no live MongoDB). Cover Req 1.4 (a gap past the timeout records a
 * MONITORING_GAP event and raises a high-risk alert) and Req 1.5 (liveness restoration records a
 * MONITORING_RESUMED event and clears the alert).
 */
class MonitoringGapWatchdogTest {

    private static final long BASE = 1_700_000_000_000L;
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
        // Seed the last-liveness marker at BASE so timing is deterministic.
        watchdog.setClock(Clock.fixed(Instant.ofEpochMilli(BASE), ZoneOffset.UTC));
    }

    // --- Req 1.4: gap past timeout records MONITORING_GAP and raises a high-risk alert ----------

    @Test
    void withinTimeoutNoGapIsDetected() {
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(BASE + TIMEOUT_MS);

        assertThat(alert).isEmpty();
        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void exceedingTimeoutRecordsMonitoringGapAndRaisesHighRiskAlert() {
        Optional<MonitoringGapAlert> alert = watchdog.checkForGap(BASE + TIMEOUT_MS + 1);

        assertThat(alert).isPresent();
        assertThat(alert.orElseThrow().isHighRisk()).isTrue();
        assertThat(alert.orElseThrow().gapMillis()).isEqualTo(TIMEOUT_MS + 1);
        assertThat(alert.orElseThrow().timeoutMillis()).isEqualTo(TIMEOUT_MS);

        assertThat(watchdog.isAlertActive()).isTrue();
        assertThat(watchdog.currentAlert()).isPresent();

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(1);
        AuditHistoryDocument record = records.get(0);
        assertThat(record.getRecordType()).isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_GAP);
        assertThat(record.getUserId()).isEqualTo(MonitoringGapWatchdog.SYSTEM_USER);
        assertThat(record.getTimestamp()).isEqualTo(BASE + TIMEOUT_MS + 1);
        assertThat(record.getDivergenceScore()).isEqualTo(1.0);
        assertThat(record.getExplanation()).isNotBlank();
    }

    @Test
    void ongoingGapRaisesExactlyOneAlert() {
        assertThat(watchdog.checkForGap(BASE + TIMEOUT_MS + 1)).isPresent();
        // Still blind, later checks must not record another MONITORING_GAP event.
        assertThat(watchdog.checkForGap(BASE + TIMEOUT_MS + 100)).isEmpty();
        assertThat(watchdog.checkForGap(BASE + TIMEOUT_MS + 5000)).isEmpty();

        assertThat(repository.findAll()).hasSize(1);
        assertThat(watchdog.isAlertActive()).isTrue();
    }

    // --- Req 1.5: restoration records MONITORING_RESUMED and clears the alert -------------------

    @Test
    void livenessRestorationRecordsMonitoringResumedAndClearsAlert() {
        watchdog.checkForGap(BASE + TIMEOUT_MS + 1);
        assertThat(watchdog.isAlertActive()).isTrue();

        long restoredAt = BASE + TIMEOUT_MS + 2000;
        watchdog.recordLiveness(restoredAt);

        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(watchdog.currentAlert()).isEmpty();

        List<AuditHistoryDocument> records = repository.findAll();
        assertThat(records).hasSize(2);
        AuditHistoryDocument resumed = records.get(1);
        assertThat(resumed.getRecordType())
                .isEqualTo(MonitoringGapWatchdog.RECORD_TYPE_MONITORING_RESUMED);
        assertThat(resumed.getTimestamp()).isEqualTo(restoredAt);
        assertThat(resumed.getUserId()).isEqualTo(MonitoringGapWatchdog.SYSTEM_USER);
    }

    @Test
    void livenessWithoutAGapRecordsNothing() {
        watchdog.recordLiveness(BASE + 1000);

        assertThat(watchdog.isAlertActive()).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void afterRestorationANewGapCanBeReportedAgain() {
        watchdog.checkForGap(BASE + TIMEOUT_MS + 1);
        long restoredAt = BASE + TIMEOUT_MS + 2000;
        watchdog.recordLiveness(restoredAt);

        // A fresh gap opens after the restored liveness marker.
        Optional<MonitoringGapAlert> second = watchdog.checkForGap(restoredAt + TIMEOUT_MS + 1);

        assertThat(second).isPresent();
        assertThat(watchdog.isAlertActive()).isTrue();
        // gap record, resumed record, second gap record.
        assertThat(repository.findAll()).hasSize(3);
    }

    // --- Configured timeout is honoured ---------------------------------------------------------

    @Test
    void usesConfiguredMonitoringGapTimeout() {
        long configuredTimeout = 2000L;
        when(thresholdConfigService.getActiveConfig())
                .thenReturn(Optional.of(configWithTimeout(configuredTimeout)));

        assertThat(watchdog.timeoutMillis()).isEqualTo(configuredTimeout);
        // Below the default 5s but above the configured 2s -> a gap must be detected.
        assertThat(watchdog.checkForGap(BASE + configuredTimeout + 1)).isPresent();
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
