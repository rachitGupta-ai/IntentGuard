package com.intentguard.assist;

/**
 * Response body for POST /api/assist/select.
 *
 * @param sessionId   session identifier
 * @param command     the selected command text
 * @param score       composite divergence score [0.0, 1.0]
 * @param action      corrective action (ALLOW, ASK, BLOCK)
 * @param explanation human-readable explanation of the decision
 * @param blocked     whether execution is refused
 */
public record SelectResponse(
        String sessionId,
        String command,
        double score,
        String action,
        String explanation,
        boolean blocked) {}
