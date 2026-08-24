package com.intentguard.watchdog;

/**
 * A high-risk Control_Tower alert raised when the Enforcement_Engine detects a monitoring gap —
 * no Audit_Feed event or hook liveness ping has been received for longer than the configured
 * monitoring-gap timeout (Req 1.4). While such an alert is active the engine is effectively blind,
 * hence its high-risk classification.
 *
 * @param detectedAtMillis  UTC epoch millis at which the gap was detected (the moment the alert was
 *                          raised)
 * @param lastLivenessMillis UTC epoch millis of the last liveness signal before the gap
 * @param gapMillis         elapsed time since the last liveness signal at detection
 * @param timeoutMillis     the configured monitoring-gap timeout that was exceeded
 * @param message           a human-readable description of the gap for the Control_Tower
 */
public record MonitoringGapAlert(
        long detectedAtMillis,
        long lastLivenessMillis,
        long gapMillis,
        long timeoutMillis,
        String message) {

    /** A monitoring-gap alert is always high-risk (Req 1.4). */
    public boolean isHighRisk() {
        return true;
    }
}
