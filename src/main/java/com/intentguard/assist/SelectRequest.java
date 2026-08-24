package com.intentguard.assist;

import java.util.Objects;

/**
 * Request body for POST /api/assist/select.
 *
 * @param sessionId    session identifier (required)
 * @param commandIndex zero-based index of the selected alternative (required)
 */
public record SelectRequest(String sessionId, int commandIndex) {
    public SelectRequest {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (commandIndex < 0) throw new IllegalArgumentException("commandIndex must be non-negative");
    }
}
