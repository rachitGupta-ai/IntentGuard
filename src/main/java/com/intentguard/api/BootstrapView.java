package com.intentguard.api;

import java.util.ArrayList;
import java.util.List;

import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.IntentSessionDocument;

/**
 * Hydration payload for the Control_Tower dashboard on a fresh page load. Projects persisted
 * MongoDB state (recent Intent_Sessions and Audit_History) into the exact same
 * {@link SessionUpdateEvent} / {@link ScoreEvent} / {@link AlertEvent} shapes the live SSE channel
 * emits, so the frontend can replay them through its existing state-transition functions.
 *
 * <p>This makes the dashboard reflect the last N days of activity immediately, rather than starting
 * empty and only filling as new live events arrive.
 *
 * @param sessions recent or still-open Intent_Sessions, oldest-first
 * @param scores   decision/score records (commands with a Corrective_Action), oldest-first
 * @param alerts   anomaly / monitoring-gap alert records, oldest-first
 * @param sinceMs  the cutoff timestamp (epoch millis) the hydration was computed from
 */
public record BootstrapView(
        List<SessionUpdateEvent> sessions,
        List<ScoreEvent> scores,
        List<AlertEvent> alerts,
        long sinceMs) {

    /** Record types that represent an anomaly/monitoring alert rather than a command decision. */
    private static boolean isAlertRecord(String recordType) {
        if (recordType == null) {
            return false;
        }
        String t = recordType.toUpperCase();
        return t.contains("ANOMALY") || t.contains("MONITORING") || t.contains("GAP");
    }

    /**
     * Builds the hydration payload from persisted documents. A session document becomes a
     * {@link SessionUpdateEvent} ({@code OPENED} when still open, else {@code CLOSED}); an audit
     * record with a Corrective_Action and command text becomes a {@link ScoreEvent}; an
     * anomaly/monitoring record becomes an {@link AlertEvent}.
     */
    public static BootstrapView from(
            List<IntentSessionDocument> sessionDocs,
            List<AuditHistoryDocument> auditDocs,
            long sinceMs) {

        List<SessionUpdateEvent> sessions = new ArrayList<>();
        if (sessionDocs != null) {
            for (IntentSessionDocument s : sessionDocs) {
                long ts = s.getEndedAt() != null ? s.getEndedAt() : s.getStartedAt();
                sessions.add(new SessionUpdateEvent(
                        s.getSessionId(),
                        s.getUserId(),
                        s.getDeclaredIntent(),
                        s.isOpen() ? "OPENED" : "CLOSED",
                        ts));
            }
        }

        List<ScoreEvent> scores = new ArrayList<>();
        List<AlertEvent> alerts = new ArrayList<>();
        if (auditDocs != null) {
            for (AuditHistoryDocument d : auditDocs) {
                if (isAlertRecord(d.getRecordType())) {
                    alerts.add(new AlertEvent(
                            d.getRecordType(),
                            d.getUserId(),
                            d.getTimestamp(),
                            true,
                            d.getExplanation() != null ? d.getExplanation() : d.getReasonCode(),
                            null));
                } else if (d.getCorrectiveAction() != null && !d.getCorrectiveAction().isBlank()) {
                    // A scored command decision (DECISION, REJECTED_TAMPER, POLICY_HIT, etc.).
                    scores.add(new ScoreEvent(
                            d.getEventId(),
                            d.getUserId(),
                            d.getDivergenceScore(),
                            d.getCorrectiveAction(),
                            d.getTimestamp(),
                            d.getExplanation()));
                }
            }
        }

        return new BootstrapView(sessions, scores, alerts, sinceMs);
    }
}
