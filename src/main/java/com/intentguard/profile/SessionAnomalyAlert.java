package com.intentguard.profile;

import java.util.List;
import java.util.Objects;

/**
 * A session-anomaly (session-hijack / account-takeover) alert raised when a sequence of a user's
 * Command_Events deviates from their Behavioral_Profile beyond the configured deviation threshold
 * (Req 10.1, 10.2).
 *
 * <p>The alert carries the <b>Behavioral_Deviation evidence</b> that triggered it — the window of
 * recent per-event Behavioral_Deviation values and their mean — so the Control_Tower can surface
 * <em>why</em> the anomaly fired and the Audit_History can persist the supporting evidence
 * (Req 10.2, 10.3).
 *
 * @param userId             the user whose event sequence tripped the detector
 * @param timestamp          UTC epoch millis of the triggering (most recent) event
 * @param meanDeviation      the mean Behavioral_Deviation over the evidence window (in [0,1])
 * @param threshold          the configured deviation threshold that was exceeded
 * @param evidenceDeviations the per-event Behavioral_Deviation values that constitute the evidence,
 *                           oldest-first; never {@code null} and never empty
 * @param message            a plain-English description of the anomaly
 */
public record SessionAnomalyAlert(
        String userId,
        long timestamp,
        double meanDeviation,
        double threshold,
        List<Double> evidenceDeviations,
        String message) {

    public SessionAnomalyAlert {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(evidenceDeviations, "evidenceDeviations must not be null");
        if (evidenceDeviations.isEmpty()) {
            throw new IllegalArgumentException("evidenceDeviations must not be empty");
        }
        Objects.requireNonNull(message, "message must not be null");
        // Defensive, unmodifiable copy so the alert is immutable.
        evidenceDeviations = List.copyOf(evidenceDeviations);
    }
}
