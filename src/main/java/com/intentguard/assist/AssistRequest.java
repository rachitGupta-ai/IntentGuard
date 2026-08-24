package com.intentguard.assist;

/**
 * Request body for POST /api/assist.
 *
 * @param query       natural-language operation description (required)
 * @param languageTag BCP-47 language tag (optional, default "en")
 * @param sessionId   existing session to continue (optional)
 */
public record AssistRequest(String query, String languageTag, String sessionId) {
    public AssistRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
    }
}
