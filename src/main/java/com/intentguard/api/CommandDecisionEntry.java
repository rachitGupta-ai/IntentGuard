package com.intentguard.api;

import com.intentguard.persistence.AuditHistoryDocument;

/**
 * Read-only projection of an {@link AuditHistoryDocument} for the User_Profiling_Screen
 * command-decision timeline (Req 2.3, 2.4, 2.5).
 *
 * <p>{@code inputOrigin} maps directly from the persisted field and may be {@code null} when not
 * recorded; the UI is responsible for displaying a fallback label (e.g. "unknown") in that case
 * (Req 2.5). All other fields are copied verbatim from the document — no decoding, reversing, or
 * unmasking is performed (Req 9.5).
 *
 * @param eventId          unique event identifier
 * @param commandText      the intercepted command text (already PII-masked at write time)
 * @param timestamp        epoch-millis of the decision
 * @param correctiveAction the decision outcome: ALLOW, ASK, or BLOCK (Req 2.3)
 * @param divergenceScore  composite divergence score in [0, 1] (Req 2.3)
 * @param reasonCode       machine-readable reason code for the decision
 * @param profileState     behavioral profile state at decision time: LEARNING or ACTIVE
 * @param inputOrigin      origin of the command signal, nullable (Req 2.5)
 */
public record CommandDecisionEntry(
        String eventId,
        String commandText,
        long timestamp,
        String correctiveAction,
        double divergenceScore,
        String reasonCode,
        String profileState,
        String inputOrigin) {

    /**
     * Projects an {@link AuditHistoryDocument} into a {@link CommandDecisionEntry}.
     *
     * <p>Fields are copied verbatim from the document. {@code inputOrigin} is preserved as-is
     * (may be {@code null}) per Req 2.5.
     *
     * @param d the source document; must not be null
     * @return the projected entry
     */
    public static CommandDecisionEntry from(AuditHistoryDocument d) {
        return new CommandDecisionEntry(
                d.getEventId(),
                d.getCommandText(),
                d.getTimestamp(),
                d.getCorrectiveAction(),
                d.getDivergenceScore(),
                d.getReasonCode(),
                d.getProfileState(),
                d.getInputOrigin()); // nullable — Req 2.5
    }
}
