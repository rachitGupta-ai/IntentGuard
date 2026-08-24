package com.intentguard.api;

/**
 * Response returned after an Administrator resolves a pending {@code ask} Command_Event (Req 12.5).
 * Echoes the event that was resolved, the chosen Corrective_Action, the recorded resolver, and the
 * {@code recordType} of the persisted Audit_History resolution record.
 */
public record AskResolutionResponse(
        String eventId, String action, String resolvedBy, String recordType, long timestamp) {
}
