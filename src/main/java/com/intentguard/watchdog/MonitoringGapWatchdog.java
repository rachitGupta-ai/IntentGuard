package com.intentguard.watchdog;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.IntentSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

/**
 * Monitoring-gap watchdog for the reference monitor (Req 1.4, 1.5).
 *
 * <p>The engine can only enforce what it observes, so a silent Audit_Feed / Shell_Hook is itself a
 * high-risk condition. This watchdog tracks the time since the last liveness signal (an Audit_Feed
 * event or a hook liveness ping). When that elapsed time exceeds the configured monitoring-gap
 * timeout (default {@value #DEFAULT_TIMEOUT_MS} ms, sourced from the active
 * {@link ThresholdConfiguration#monitoringGapTimeoutMs()}), it records a {@code MONITORING_GAP}
 * event in the Audit_History and raises a high-risk {@link MonitoringGapAlert} (Req 1.4). When
 * liveness is restored it records a {@code MONITORING_RESUMED} event and clears the alert
 * (Req 1.5).
 *
 * <h2>Deterministic timing</h2>
 * <p>Gap detection is driven by explicit timestamps: {@link #recordLiveness(long)} advances the
 * last-liveness marker and {@link #checkForGap(long)} evaluates the elapsed time against the
 * timeout. This keeps the transition logic fully deterministic and lets tests drive time exactly.
 * A {@link Clock} (overridable in tests, defaulting to {@link Clock#systemUTC()}) backs the no-arg
 * {@link #heartbeat()} / {@link #checkForGap()} conveniences and seeds the initial liveness marker
 * so the engine does not report a gap the instant it starts.
 *
 * <h2>Raising within 2 seconds (Req 1.4)</h2>
 * <p>{@link #checkForGap} raises the alert synchronously the moment it observes the timeout has
 * been exceeded. A scheduler polling this method at an interval below 2 seconds therefore
 * guarantees the alert is raised within 2 seconds of the gap opening. The polling scheduler is a
 * deployment concern; this class exposes {@link #checkForGap(long)} for that scheduler (and tests)
 * to call and deliberately starts no scheduler of its own.
 *
 * <p>A gap raises exactly one alert: repeated {@link #checkForGap} calls while still blind do not
 * record additional {@code MONITORING_GAP} events. A fresh gap can only be reported after liveness
 * has been restored.
 *
 * <h2>Concurrency</h2>
 * <p>All state transitions are guarded by a monitor so liveness updates and gap checks are atomic.
 */
@Service
public class MonitoringGapWatchdog {

    /** Fallback monitoring-gap timeout when no active Threshold_Configuration is available. */
    static final long DEFAULT_TIMEOUT_MS = 5000L;

    /** Audit_History record type for a detected monitoring gap (Req 1.4). */
    static final String RECORD_TYPE_MONITORING_GAP = "MONITORING_GAP";

    /** Audit_History record type for restored monitoring (Req 1.5). */
    static final String RECORD_TYPE_MONITORING_RESUMED = "MONITORING_RESUMED";

    /** Synthetic user id under which engine self-monitoring records are stored. */
    static final String SYSTEM_USER = "SYSTEM";

    private final AuditHistoryRepository auditHistory;
    private final ThresholdConfigurationService thresholdConfigService;
    private volatile Clock clock = Clock.systemUTC();

    private final Object lock = new Object();
    private long lastLivenessMillis;
    private boolean alertActive;
    private MonitoringGapAlert currentAlert;

    public MonitoringGapWatchdog(
            AuditHistoryRepository auditHistory, ThresholdConfigurationService thresholdConfigService) {
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
        this.thresholdConfigService =
                Objects.requireNonNull(thresholdConfigService, "thresholdConfigService must not be null");
        // Seed the liveness marker at construction so a freshly started engine is not immediately
        // considered blind.
        this.lastLivenessMillis = clock.millis();
    }

    /**
     * Test seam: overrides the clock backing the no-arg conveniences and re-seeds the initial
     * liveness marker so tests start from a known point.
     */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        synchronized (lock) {
            this.lastLivenessMillis = clock.millis();
        }
    }

    /**
     * Records a liveness signal (an Audit_Feed event or hook liveness ping) observed at
     * {@code timestampMillis}. If a monitoring-gap alert was active, this is a restoration: a
     * {@code MONITORING_RESUMED} event is recorded and the alert cleared (Req 1.5).
     */
    public void recordLiveness(long timestampMillis) {
        synchronized (lock) {
            if (timestampMillis > lastLivenessMillis) {
                lastLivenessMillis = timestampMillis;
            }
            if (alertActive) {
                recordResumed(timestampMillis);
                alertActive = false;
                currentAlert = null;
            }
        }
    }

    /** Records a liveness signal at the current clock time. */
    public void heartbeat() {
        recordLiveness(clock.millis());
    }

    /**
     * Evaluates whether a monitoring gap exists as of {@code nowMillis}. If the elapsed time since
     * the last liveness signal exceeds the configured timeout and no alert is yet active, records a
     * {@code MONITORING_GAP} event and raises a high-risk alert (Req 1.4).
     *
     * @return the alert raised by this call, or {@link Optional#empty()} if no new gap was detected
     *         (either liveness is current, or an alert is already active for the ongoing gap)
     */
    public Optional<MonitoringGapAlert> checkForGap(long nowMillis) {
        synchronized (lock) {
            long timeout = timeoutMillis();
            long elapsed = nowMillis - lastLivenessMillis;
            if (elapsed > timeout && !alertActive) {
                MonitoringGapAlert alert = new MonitoringGapAlert(
                        nowMillis,
                        lastLivenessMillis,
                        elapsed,
                        timeout,
                        describeGap(elapsed, timeout));
                recordGap(alert);
                alertActive = true;
                currentAlert = alert;
                return Optional.of(alert);
            }
            return Optional.empty();
        }
    }

    /** Evaluates for a monitoring gap at the current clock time. */
    public Optional<MonitoringGapAlert> checkForGap() {
        return checkForGap(clock.millis());
    }

    /** Whether a high-risk monitoring-gap alert is currently active (Req 1.4, 1.5). */
    public boolean isAlertActive() {
        synchronized (lock) {
            return alertActive;
        }
    }

    /** The active monitoring-gap alert, if one is raised. */
    public Optional<MonitoringGapAlert> currentAlert() {
        synchronized (lock) {
            return Optional.ofNullable(currentAlert);
        }
    }

    /** The last liveness timestamp the watchdog has observed. */
    public long lastLivenessMillis() {
        synchronized (lock) {
            return lastLivenessMillis;
        }
    }

    /** The monitoring-gap timeout currently in effect (from active config, else the default). */
    public long timeoutMillis() {
        return thresholdConfigService
                .getActiveConfig()
                .map(ThresholdConfiguration::monitoringGapTimeoutMs)
                .orElse(DEFAULT_TIMEOUT_MS);
    }

    private void recordGap(MonitoringGapAlert alert) {
        AuditHistoryDocument record = baseRecord(alert.detectedAtMillis());
        record.setRecordType(RECORD_TYPE_MONITORING_GAP);
        record.setReasonCode(RECORD_TYPE_MONITORING_GAP);
        // A monitoring gap is a high-risk condition; record it at the maximum divergence score.
        record.setDivergenceScore(1.0);
        record.setExplanation(alert.message());
        auditHistory.save(record);
    }

    private void recordResumed(long timestampMillis) {
        AuditHistoryDocument record = baseRecord(timestampMillis);
        record.setRecordType(RECORD_TYPE_MONITORING_RESUMED);
        record.setReasonCode(RECORD_TYPE_MONITORING_RESUMED);
        record.setDivergenceScore(0.0);
        record.setExplanation("Monitoring resumed: a liveness signal was received and the "
                + "monitoring-gap alert was cleared.");
        auditHistory.save(record);
    }

    private AuditHistoryDocument baseRecord(long timestampMillis) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(UUID.randomUUID().toString());
        record.setUserId(SYSTEM_USER);
        record.setTimestamp(timestampMillis);
        record.setIntentPresent(false);
        record.setIntentSource(IntentSource.NONE.name());
        return record;
    }

    private static String describeGap(long elapsedMillis, long timeoutMillis) {
        return String.format(
                "Monitoring gap detected: no Audit_Feed event or hook liveness ping for %d ms, "
                        + "exceeding the configured %d ms monitoring-gap timeout. The engine is "
                        + "blind until liveness is restored.",
                elapsedMillis, timeoutMillis);
    }
}
