package com.intentguard.assist;

/**
 * Thrown when an operator exceeds the per-minute query rate limit for the NL Operations Assistant.
 */
public class AssistRateLimitException extends RuntimeException {

    private final long retryAfterMs;

    public AssistRateLimitException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * Returns the recommended wait time in milliseconds before the operator should retry.
     */
    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
