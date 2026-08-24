package com.intentguard.assist;

import java.util.Objects;

/**
 * Request body for POST /api/assist/confirm.
 *
 * @param sessionId    session identifier (required)
 * @param commandIndex zero-based index of the command to execute (required)
 */
public record ConfirmRequest(String sessionId, int commandIndex) {
    public ConfirmRequest {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (commandIndex < 0) throw new IllegalArgumentException("commandIndex must be non-negative");
    }
}
