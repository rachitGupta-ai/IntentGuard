package com.intentguard.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.intentguard.domain.ComponentId;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.ComponentScoreDocument;

/**
 * Session-anomaly (session-hijack / account-takeover) detector (Req 10.1, 10.2, 10.3).
 *
 * <p>Session hijack is surfaced as a <em>behavioral-fingerprint mismatch</em>: rather than looking
 * at a single command, the detector watches a rolling window of a user's most recent
 * Behavioral_Deviation values and fires when the <b>sustained</b> deviation over that window
 * exceeds a configured threshold. A lone off-baseline command therefore does not trip it, but a
 * run of consistently high-deviation events — the signature of a different actor driving the
 * session — does.
 *
 * <h2>Detection rule (deterministic)</h2>
 * <p>For each user the detector keeps the last {@link #windowSize} Behavioral_Deviation values.
 * When the window is full and its arithmetic mean is {@code >=} {@link #deviationThreshold}, a
 * {@link SessionAnomalyAlert} is raised carrying that window as evidence (Req 10.1, 10.2). The
 * window is then <b>cleared</b>, so a single sustained anomaly raises exactly one alert; a fresh
 * full window of high-deviation events is required before another can fire. Given the same
 * sequence of inputs the detector always produces the same alerts — it holds no randomness or
 * wall-clock dependence (the alert timestamp is taken from the triggering event).
 *
 * <h2>Persistence (Req 10.3)</h2>
 * <p>Every raised alert is persisted to the Audit_History as a {@code SESSION_ANOMALY} record whose
 * embedded component scores are the evidence deviations (each tagged
 * {@link ComponentId#BEHAVIORAL_DEVIATION}) and whose {@code divergenceScore} is the mean, so the
 * supporting evidence survives restarts and is reviewable.
 *
 * <h2>Control_Tower bridge</h2>
 * <p>Raised alerts are retained in-memory and exposed via {@link #raisedAlerts()} and
 * {@link #lastAlertFor(String)} so the Control_Tower can surface them and tests can assert on them.
 *
 * <h2>Concurrency</h2>
 * <p>Per-user windows are guarded by a per-user lock so a user's observe cycle is atomic across
 * threads; the raised-alert log is a concurrent queue.
 */
@Service
public class SessionAnomalyDetector {

    /** Default sustained-deviation threshold over the window, in [0,1]. */
    public static final double DEFAULT_DEVIATION_THRESHOLD = 0.6;

    /** Default number of recent events forming the detection window. */
    public static final int DEFAULT_WINDOW_SIZE = 3;

    private final AuditHistoryRepository auditHistory;
    private final double deviationThreshold;
    private final int windowSize;

    private final ConcurrentHashMap<String, Deque<Double>> windowsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SessionAnomalyAlert> raisedAlerts = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, SessionAnomalyAlert> lastAlertByUser = new ConcurrentHashMap<>();

    /**
     * Spring entry point: builds a detector with the {@link #DEFAULT_DEVIATION_THRESHOLD default
     * threshold} and {@link #DEFAULT_WINDOW_SIZE default window}.
     */
    @Autowired
    public SessionAnomalyDetector(AuditHistoryRepository auditHistory) {
        this(auditHistory, DEFAULT_DEVIATION_THRESHOLD, DEFAULT_WINDOW_SIZE);
    }

    /**
     * Builds a detector with an explicit threshold and window (used by tests and scenario replays).
     *
     * @param auditHistory       repository the {@code SESSION_ANOMALY} records are persisted to
     * @param deviationThreshold sustained-deviation threshold over the window, in [0,1]
     * @param windowSize         number of recent events forming the detection window (>= 1)
     */
    public SessionAnomalyDetector(AuditHistoryRepository auditHistory, double deviationThreshold, int windowSize) {
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
        if (Double.isNaN(deviationThreshold) || deviationThreshold < 0.0 || deviationThreshold > 1.0) {
            throw new IllegalArgumentException("deviationThreshold must be in [0.0, 1.0]: " + deviationThreshold);
        }
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1: " + windowSize);
        }
        this.deviationThreshold = deviationThreshold;
        this.windowSize = windowSize;
    }

    /**
     * Observes the next Behavioral_Deviation value for a user's Command_Event and, if the sustained
     * deviation over the rolling window now exceeds the configured threshold, raises and persists a
     * {@link SessionAnomalyAlert} (Req 10.1, 10.2, 10.3).
     *
     * @param userId              the user the event belongs to
     * @param behavioralDeviation the event's Behavioral_Deviation score, in [0,1]
     * @param timestamp           UTC epoch millis of the event (used as the alert timestamp)
     * @return the raised alert, or {@link Optional#empty()} when no anomaly is detected
     */
    public Optional<SessionAnomalyAlert> observe(String userId, double behavioralDeviation, long timestamp) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (Double.isNaN(behavioralDeviation) || behavioralDeviation < 0.0 || behavioralDeviation > 1.0) {
            throw new IllegalArgumentException(
                    "behavioralDeviation must be in [0.0, 1.0]: " + behavioralDeviation);
        }
        synchronized (lockFor(userId)) {
            Deque<Double> window = windowsByUser.computeIfAbsent(userId, key -> new ArrayDeque<>());
            window.addLast(behavioralDeviation);
            while (window.size() > windowSize) {
                window.removeFirst();
            }
            if (window.size() < windowSize) {
                return Optional.empty();
            }
            double mean = mean(window);
            if (mean < deviationThreshold) {
                return Optional.empty();
            }
            List<Double> evidence = new ArrayList<>(window);
            // Clear so a single sustained anomaly raises exactly one alert.
            window.clear();
            SessionAnomalyAlert alert = new SessionAnomalyAlert(
                    userId, timestamp, mean, deviationThreshold, evidence, describe(userId, mean, evidence));
            persist(alert);
            raisedAlerts.add(alert);
            lastAlertByUser.put(userId, alert);
            return Optional.of(alert);
        }
    }

    /**
     * Observes an ordered sequence of Behavioral_Deviation values for a user (convenience over
     * repeated {@link #observe}), returning every alert raised while processing the sequence.
     *
     * @param userId     the user the events belong to
     * @param deviations the ordered per-event Behavioral_Deviation values, each in [0,1]
     * @param timestamp  UTC epoch millis stamped on any raised alert
     * @return the alerts raised over the sequence, in the order they fired
     */
    public List<SessionAnomalyAlert> observeSequence(String userId, List<Double> deviations, long timestamp) {
        Objects.requireNonNull(deviations, "deviations must not be null");
        List<SessionAnomalyAlert> alerts = new ArrayList<>();
        for (double deviation : deviations) {
            observe(userId, deviation, timestamp).ifPresent(alerts::add);
        }
        return alerts;
    }

    /** All session-anomaly alerts raised so far, in the order they fired (for the Control_Tower). */
    public List<SessionAnomalyAlert> raisedAlerts() {
        return new ArrayList<>(raisedAlerts);
    }

    /** The most recent session-anomaly alert raised for {@code userId}, if any. */
    public Optional<SessionAnomalyAlert> lastAlertFor(String userId) {
        return Optional.ofNullable(lastAlertByUser.get(userId));
    }

    /** The configured sustained-deviation threshold. */
    public double deviationThreshold() {
        return deviationThreshold;
    }

    /** The configured detection window size. */
    public int windowSize() {
        return windowSize;
    }

    // --- internals ----------------------------------------------------------------------------

    private void persist(SessionAnomalyAlert alert) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId("session-anomaly-" + alert.userId() + "-" + alert.timestamp());
        record.setUserId(alert.userId());
        record.setTimestamp(alert.timestamp());
        record.setRecordType("SESSION_ANOMALY");
        record.setDivergenceScore(alert.meanDeviation());
        record.setExplanation(alert.message());
        // Embed the Behavioral_Deviation evidence as component scores (Req 10.2, 10.3).
        List<ComponentScoreDocument> evidence = new ArrayList<>();
        for (Double deviation : alert.evidenceDeviations()) {
            evidence.add(new ComponentScoreDocument(
                    ComponentId.BEHAVIORAL_DEVIATION.name(), deviation, 0.0, "session-anomaly evidence"));
        }
        record.setComponents(evidence);
        auditHistory.save(record);
    }

    private static double mean(Deque<Double> window) {
        double sum = 0.0;
        for (double value : window) {
            sum += value;
        }
        return sum / window.size();
    }

    private static String describe(String userId, double mean, List<Double> evidence) {
        return String.format(
                "Session-anomaly detected for user '%s': mean Behavioral_Deviation %.3f over the last %d events"
                        + " exceeded the configured deviation threshold. Evidence deviations: %s.",
                userId, mean, evidence.size(), evidence);
    }

    private Object lockFor(String userId) {
        return userLocks.computeIfAbsent(userId, key -> new Object());
    }
}
