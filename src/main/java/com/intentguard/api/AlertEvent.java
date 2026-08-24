package com.intentguard.api;

import java.util.List;

import com.intentguard.profile.SessionAnomalyAlert;
import com.intentguard.watchdog.MonitoringGapAlert;

/**
 * A live "alert" event pushed to subscribed Control_Tower clients (Req 12.6). It carries the two
 * alert kinds the engine raises:
 * <ul>
 *   <li>{@code SESSION_ANOMALY} — a session-hijack / account-takeover alert with its
 *       Behavioral_Deviation evidence ({@link SessionAnomalyAlert}, Req 10.1–10.3);</li>
 *   <li>{@code MONITORING_GAP} — a high-risk monitoring-gap alert ({@link MonitoringGapAlert},
 *       Req 1.4).</li>
 * </ul>
 *
 * @param alertType          the alert kind: {@code SESSION_ANOMALY} or {@code MONITORING_GAP}
 * @param userId             the affected user, or {@code null} for engine-wide alerts (e.g. a
 *                           monitoring gap)
 * @param timestamp          UTC epoch millis at which the alert was raised
 * @param highRisk           whether the alert is high-risk
 * @param message            a plain-English description of the alert
 * @param evidenceDeviations the Behavioral_Deviation evidence for a session anomaly, or
 *                           {@code null}
 */
public record AlertEvent(
        String alertType,
        String userId,
        long timestamp,
        boolean highRisk,
        String message,
        List<Double> evidenceDeviations) {

    public static final String TYPE_SESSION_ANOMALY = "SESSION_ANOMALY";
    public static final String TYPE_MONITORING_GAP = "MONITORING_GAP";

    /** Projects a {@link SessionAnomalyAlert} into a live {@link AlertEvent}. */
    public static AlertEvent fromSessionAnomaly(SessionAnomalyAlert alert) {
        return new AlertEvent(
                TYPE_SESSION_ANOMALY,
                alert.userId(),
                alert.timestamp(),
                true,
                alert.message(),
                alert.evidenceDeviations());
    }

    /** Projects a {@link MonitoringGapAlert} into a live {@link AlertEvent}. */
    public static AlertEvent fromMonitoringGap(MonitoringGapAlert alert) {
        return new AlertEvent(
                TYPE_MONITORING_GAP,
                null,
                alert.detectedAtMillis(),
                alert.isHighRisk(),
                alert.message(),
                null);
    }
}
