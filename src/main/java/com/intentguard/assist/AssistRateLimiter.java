package com.intentguard.assist;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-operator sliding-window rate limiter for the NL Operations Assistant.
 *
 * <p>Enforces a maximum number of queries per operator per 60-second window.
 * The window slides based on the timestamp provided to {@link #checkAndRecord},
 * evicting entries older than 60 seconds before checking the count.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} and {@link ConcurrentLinkedDeque}
 * so concurrent requests from the same operator are handled safely.
 *
 * @see AssistProperties#getRateLimitPerMinute()
 * @see AssistRateLimitException
 */
@Component
public class AssistRateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final ConcurrentHashMap<String, Deque<Long>> operatorWindows = new ConcurrentHashMap<>();
    private final int maxPerMinute;

    public AssistRateLimiter(AssistProperties properties) {
        this.maxPerMinute = properties.getRateLimitPerMinute();
    }

    /**
     * Checks whether the operator is within the rate limit and, if so, records the current
     * timestamp. If the operator has already reached the maximum number of queries in the
     * current 60-second window, throws {@link AssistRateLimitException} with the recommended
     * retry-after duration.
     *
     * @param operatorId the operator's unique identifier
     * @param nowMs      the current time in epoch milliseconds
     * @throws AssistRateLimitException if the per-minute limit is exceeded
     */
    public void checkAndRecord(String operatorId, long nowMs) {
        Deque<Long> window = operatorWindows.computeIfAbsent(operatorId, k -> new ConcurrentLinkedDeque<>());

        // Evict entries older than 60 seconds
        while (!window.isEmpty() && window.peekFirst() <= nowMs - WINDOW_MS) {
            window.pollFirst();
        }

        if (window.size() >= maxPerMinute) {
            long oldestRemaining = window.peekFirst();
            long retryAfterMs = WINDOW_MS - (nowMs - oldestRemaining);
            throw new AssistRateLimitException(
                    "Rate limit exceeded. Max " + maxPerMinute + " queries per minute.",
                    retryAfterMs);
        }

        window.addLast(nowMs);
    }
}
