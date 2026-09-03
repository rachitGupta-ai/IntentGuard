package com.intentguard.api;

import com.intentguard.assist.AssistAuditDocument;

import java.util.List;

/**
 * Read-only projection of an {@link AssistAuditDocument} QUERY record for the
 * User_Profiling_Screen assistant-queries category (Req 4.1, 4.2).
 *
 * <p>Only QUERY records carry a reliable {@code operatorId} and contain the data Req 4 requires
 * ({@code queryEnglish} and the ordered {@code generatedCommands} list); SELECTION/EXECUTION/BLOCK
 * records are intentionally out of scope for this view.
 *
 * <p>The {@code id} field is the MongoDB {@code _id} hex string as persisted on the document,
 * used as the deterministic tie-breaking key when ordering within the same millisecond (Req 4.1).
 *
 * <p>The UI is responsible for truncating {@code queryEnglish} to 2 000 characters for display
 * and showing a truncation indicator when the original exceeds that limit (Req 4.3); the API
 * returns the full text so no information is silently dropped server-side.
 *
 * @param id                the MongoDB _id hex string (Req 4.1 tie-break key)
 * @param queryEnglish      the full English query text submitted by the operator (Req 4.3)
 * @param generatedCommands the command alternatives generated in response, in generation order (Req 4.2)
 * @param timestamp         epoch-millis of the QUERY event
 */
public record AssistQueryView(
        String id,
        String queryEnglish,
        List<String> generatedCommands,
        long timestamp) {

    /**
     * Projects an {@link AssistAuditDocument} QUERY record into an {@link AssistQueryView}.
     *
     * <p>The caller is responsible for ensuring {@code d} is a QUERY record; this factory
     * performs no {@code eventType} check and copies fields verbatim (Req 9.5).
     *
     * @param d the source QUERY document; must not be null
     * @return the projected view
     */
    public static AssistQueryView from(AssistAuditDocument d) {
        return new AssistQueryView(
                d.getId(),
                d.getQueryEnglish(),
                d.getGeneratedCommands() != null ? List.copyOf(d.getGeneratedCommands()) : List.of(),
                d.getTimestamp());
    }
}
