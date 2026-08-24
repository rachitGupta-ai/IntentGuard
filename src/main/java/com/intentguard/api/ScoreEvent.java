package com.intentguard.api;

/**
 * A live "score" event pushed to subscribed Control_Tower clients whenever the pipeline reaches a
 * Corrective_Action for a Command_Event (Req 12.6). It is the lightweight projection of the
 * persisted Audit_History record that the dashboard needs to render the risk timeline and the
 * intent-vs-action divergence view in near real time.
 *
 * @param eventId         the Command_Event id the score belongs to
 * @param userId          the user (or agent's human principal) the event belongs to
 * @param divergenceScore the composite Divergence_Score in {@code [0,1]}
 * @param action          the Corrective_Action taken ({@code ALLOW} / {@code ASK} / {@code BLOCK})
 * @param timestamp       UTC epoch millis of the scored event
 * @param explanation     the plain-English Explanation for a flagged decision, or {@code null} for
 *                        an allowed event
 */
public record ScoreEvent(
        String eventId,
        String userId,
        double divergenceScore,
        String action,
        long timestamp,
        String explanation) {
}
