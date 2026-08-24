package com.intentguard.semantic;

import java.util.Objects;

/**
 * The contribution of the per-session intent-drift tracker after recording one event's drift
 * (Req 8.3, 8.4).
 *
 * @param sessionId       the Intent_Session the drift was accumulated for
 * @param cumulativeDrift the session's total {@code IntentDrift} after this event
 * @param alertRaised     whether cumulative drift now exceeds the configured threshold, raising a
 *                        session-level drift alert on the Control_Tower (Req 8.3)
 * @param recorded        whether the drift alert was recorded in the Audit_History; always equal to
 *                        {@code alertRaised} (an alert is recorded exactly when it is raised, Req 8.4)
 * @param timestamp       the UTC epoch-millis instant the contribution was evaluated, from the
 *                        tracker's injectable clock
 */
public record IntentDriftResult(
        String sessionId,
        double cumulativeDrift,
        boolean alertRaised,
        boolean recorded,
        long timestamp) {

    public IntentDriftResult {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
    }
}
