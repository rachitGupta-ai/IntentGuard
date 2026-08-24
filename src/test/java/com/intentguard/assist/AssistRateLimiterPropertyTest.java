package com.intentguard.assist;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link AssistRateLimiter}.
 *
 * <p><b>Validates: Requirements 8.1, 8.2</b>
 *
 * <p>Property 12: Rate limiting enforcement — for any operator submitting N > rateLimitPerMinute
 * queries in 60s, the (N+1)th throws; for N <= limit, all succeed.
 */
class AssistRateLimiterPropertyTest {

    private static final long WINDOW_MS = 60_000;

    /**
     * Helper: creates an AssistRateLimiter with the given maxPerMinute.
     */
    private AssistRateLimiter createLimiter(int maxPerMinute) {
        AssistProperties props = new AssistProperties();
        props.setRateLimitPerMinute(maxPerMinute);
        return new AssistRateLimiter(props);
    }

    // --- Property 1: For N <= maxPerMinute, all N queries within 60s succeed ---

    /**
     * Property: submitting exactly N queries (where N <= maxPerMinute) within a 60-second
     * window all succeed without throwing.
     */
    @Property(tries = 100)
    void queriesWithinLimitAllSucceed(
            @ForAll @IntRange(min = 1, max = 5) int maxPerMinute,
            @ForAll("operatorIds") String operatorId) {

        AssistRateLimiter limiter = createLimiter(maxPerMinute);
        long baseTime = 1_000_000L;

        // Submit exactly maxPerMinute queries — all should succeed
        assertThatCode(() -> {
            for (int i = 0; i < maxPerMinute; i++) {
                // Spread queries within the 60s window
                limiter.checkAndRecord(operatorId, baseTime + (i * 1000L));
            }
        }).doesNotThrowAnyException();
    }

    // --- Property 2: The (maxPerMinute + 1)th query within 60s throws ---

    /**
     * Property: after maxPerMinute queries within a 60-second window, the next query
     * throws AssistRateLimitException.
     */
    @Property(tries = 100)
    void queryBeyondLimitThrows(
            @ForAll @IntRange(min = 1, max = 5) int maxPerMinute,
            @ForAll("operatorIds") String operatorId) {

        AssistRateLimiter limiter = createLimiter(maxPerMinute);
        long baseTime = 1_000_000L;

        // Fill the window up to the limit
        for (int i = 0; i < maxPerMinute; i++) {
            limiter.checkAndRecord(operatorId, baseTime + (i * 1000L));
        }

        // The (maxPerMinute + 1)th query should throw
        long overflowTime = baseTime + (maxPerMinute * 1000L);
        assertThatThrownBy(() -> limiter.checkAndRecord(operatorId, overflowTime))
                .isInstanceOf(AssistRateLimitException.class);
    }

    // --- Property 3: After 60s passes, the window resets and queries succeed again ---

    /**
     * Property: once the sliding window expires (>= 60s after the earliest query),
     * new queries are allowed again.
     */
    @Property(tries = 100)
    void windowResetsAfterSixtySeconds(
            @ForAll @IntRange(min = 1, max = 5) int maxPerMinute,
            @ForAll("operatorIds") String operatorId) {

        AssistRateLimiter limiter = createLimiter(maxPerMinute);
        long baseTime = 1_000_000L;

        // Fill the window completely
        for (int i = 0; i < maxPerMinute; i++) {
            limiter.checkAndRecord(operatorId, baseTime + (i * 100L));
        }

        // Advance time past the window (60s after the first entry)
        long afterWindow = baseTime + WINDOW_MS;

        // Should succeed because the old entries are evicted
        assertThatCode(() -> limiter.checkAndRecord(operatorId, afterWindow))
                .doesNotThrowAnyException();
    }

    // --- Property 4: Different operators have independent rate limits ---

    /**
     * Property: one operator exceeding their limit does not affect another operator's
     * ability to submit queries.
     */
    @Property(tries = 100)
    void operatorsHaveIndependentLimits(
            @ForAll @IntRange(min = 1, max = 5) int maxPerMinute) {

        AssistRateLimiter limiter = createLimiter(maxPerMinute);
        long baseTime = 1_000_000L;
        String operatorA = "operator-alpha";
        String operatorB = "operator-beta";

        // Exhaust operator A's limit
        for (int i = 0; i < maxPerMinute; i++) {
            limiter.checkAndRecord(operatorA, baseTime + (i * 1000L));
        }

        // Operator A is now rate-limited
        long overflowTime = baseTime + (maxPerMinute * 1000L);
        assertThatThrownBy(() -> limiter.checkAndRecord(operatorA, overflowTime))
                .isInstanceOf(AssistRateLimitException.class);

        // Operator B should still be able to submit queries
        assertThatCode(() -> {
            for (int i = 0; i < maxPerMinute; i++) {
                limiter.checkAndRecord(operatorB, baseTime + (i * 1000L));
            }
        }).doesNotThrowAnyException();
    }

    // --- Property 5: retryAfterMs is always > 0 and <= 60000 ---

    /**
     * Property: when a rate limit exception is thrown, the retryAfterMs value is
     * strictly positive and at most 60000 (the window size).
     */
    @Property(tries = 100)
    void retryAfterMsIsWithinValidBounds(
            @ForAll @IntRange(min = 1, max = 5) int maxPerMinute,
            @ForAll("operatorIds") String operatorId) {

        AssistRateLimiter limiter = createLimiter(maxPerMinute);
        long baseTime = 1_000_000L;

        // Fill the window
        for (int i = 0; i < maxPerMinute; i++) {
            limiter.checkAndRecord(operatorId, baseTime + (i * 1000L));
        }

        // Trigger the exception at various times within the window
        long overflowTime = baseTime + (maxPerMinute * 1000L);
        try {
            limiter.checkAndRecord(operatorId, overflowTime);
            // Should not reach here
            assertThat(false).as("Expected AssistRateLimitException").isTrue();
        } catch (AssistRateLimitException ex) {
            assertThat(ex.getRetryAfterMs())
                    .as("retryAfterMs should be > 0")
                    .isGreaterThan(0);
            assertThat(ex.getRetryAfterMs())
                    .as("retryAfterMs should be <= 60000 (window size)")
                    .isLessThanOrEqualTo(WINDOW_MS);
        }
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> operatorIds() {
        return Arbitraries.strings()
                .ofMinLength(3)
                .ofMaxLength(20)
                .alpha()
                .map(s -> "op-" + s);
    }
}
