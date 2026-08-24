package com.intentguard.velocity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-guardrails, Property 23: Velocity and burst guardrails escalate and are
 * recorded (Stretch).
 *
 * <p>For any Actor whose Command_Event count within the configured rate window exceeds the rate
 * limit, the Corrective_Action floor is at least ASK; for any event whose inter-command interval
 * deviates from the profile's mean by more than the burst threshold, the effective Divergence_Score
 * is at least the burst-anomaly floor; for any rate exceeding the velocity session-anomaly
 * threshold, a session-anomaly alert is raised; and any such change is carried on the triggering
 * guardrail ids for recording in the Audit_History and naming in the Explanation
 * (Validates: Requirements 5.1, 5.2, 5.3, 5.4).
 *
 * <p>The guard is exercised directly with an in-memory {@link MutableClock} (no live Mongo, no
 * shared GuardrailContext), which is the guard's self-contained contribution to the chain.
 */
class VelocityGuardProperties {

    private static final long WINDOW_MS = 60_000L;

    // --- Req 5.1: exceeding the rate limit raises the floor to at least ASK -------------------

    @Property(tries = 200)
    void exceedingRateLimitRaisesFloorToAtLeastAsk(
            @ForAll @IntRange(min = 1, max = 10) int rateLimit,
            @ForAll @IntRange(min = 1, max = 8) int overBy) {

        // Session-anomaly threshold kept well above the count so only the rate-limit floor is under
        // test here.
        int sessionThreshold = rateLimit + overBy + 100;
        VelocityConfig cfg = new VelocityConfig(rateLimit, WINDOW_MS, 5_000L, 0.85, sessionThreshold);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        int count = rateLimit + overBy; // strictly exceeds the limit
        VelocityResult last = null;
        for (int i = 0; i < count; i++) {
            // Fixed clock: every arrival falls within the same rate window.
            last = guard.evaluate(event("alice", "ls"));
        }

        assertThat(last).isNotNull();
        assertThat(last.floor().ordinal()).isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        assertThat(last.triggeredGuardrailIds()).contains(VelocityGuard.RATE_LIMIT_TRIGGER_ID);
    }

    @Property(tries = 200)
    void atOrBelowRateLimitDoesNotRaiseTheFloor(
            @ForAll @IntRange(min = 2, max = 12) int rateLimit,
            @ForAll @IntRange(min = 1, max = 12) int rawCount) {

        int count = Math.min(rawCount, rateLimit); // never exceeds the limit
        VelocityConfig cfg = new VelocityConfig(rateLimit, WINDOW_MS, 5_000L, 0.85, rateLimit + 100);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        VelocityResult last = null;
        for (int i = 0; i < count; i++) {
            last = guard.evaluate(event("alice", "ls"));
        }

        assertThat(last).isNotNull();
        assertThat(last.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(last.triggeredGuardrailIds()).doesNotContain(VelocityGuard.RATE_LIMIT_TRIGGER_ID);
    }

    // --- Req 5.2: a burst beyond the threshold raises the burst-anomaly score floor ------------

    @Property(tries = 200)
    void burstBeyondThresholdRaisesScoreFloorToBurstAnomalyFloor(
            @ForAll @LongRange(min = 0L, max = 20_000L) long meanInterCommandMs,
            @ForAll @LongRange(min = 0L, max = 20_000L) long interval,
            @ForAll @LongRange(min = 0L, max = 5_000L) long burstThresholdMs,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double burstAnomalyFloor) {

        // Rate + session thresholds high so only the burst branch is under test.
        VelocityConfig cfg = new VelocityConfig(1_000, WINDOW_MS, burstThresholdMs, burstAnomalyFloor, 1_000);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        // First event establishes the actor's last arrival (no previous => no burst).
        VelocityResult first = guard.evaluate(event("alice", "ls"), meanInterCommandMs);
        assertThat(first.scoreFloor()).isEmpty();

        // Second event after the generated interval.
        clock.advance(interval);
        VelocityResult second = guard.evaluate(event("alice", "ls"), meanInterCommandMs);

        boolean isBurst = Math.abs(interval - meanInterCommandMs) > burstThresholdMs;
        if (isBurst) {
            assertThat(second.scoreFloor()).isPresent();
            assertThat(second.scoreFloor().getAsDouble()).isEqualTo(burstAnomalyFloor);
            assertThat(second.triggeredGuardrailIds()).contains(VelocityGuard.BURST_TRIGGER_ID);
        } else {
            assertThat(second.scoreFloor()).isEmpty();
            assertThat(second.triggeredGuardrailIds()).doesNotContain(VelocityGuard.BURST_TRIGGER_ID);
        }
    }

    @Property(tries = 200)
    void unknownProfileMeanDisablesBurstDetection(
            @ForAll @LongRange(min = 0L, max = 20_000L) long interval) {

        VelocityConfig cfg = new VelocityConfig(1_000, WINDOW_MS, 0L, 0.85, 1_000);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        guard.evaluate(event("alice", "ls")); // unknown mean overload
        clock.advance(interval);
        VelocityResult second = guard.evaluate(event("alice", "ls")); // still unknown mean

        assertThat(second.scoreFloor()).isEmpty();
        assertThat(second.triggeredGuardrailIds()).doesNotContain(VelocityGuard.BURST_TRIGGER_ID);
    }

    // --- Req 5.3: exceeding the session-anomaly threshold flags a session anomaly --------------

    @Property(tries = 200)
    void exceedingSessionAnomalyThresholdFlagsAnomalyAndRecordsTrigger(
            @ForAll @IntRange(min = 1, max = 8) int rateLimit,
            @ForAll @IntRange(min = 0, max = 8) int gap,
            @ForAll @IntRange(min = 1, max = 6) int overBy) {

        int sessionThreshold = rateLimit + gap;
        VelocityConfig cfg = new VelocityConfig(rateLimit, WINDOW_MS, 5_000L, 0.85, sessionThreshold);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        int count = sessionThreshold + overBy; // strictly exceeds the session-anomaly threshold
        VelocityResult last = null;
        for (int i = 0; i < count; i++) {
            last = guard.evaluate(event("alice", "ls"));
        }

        assertThat(last).isNotNull();
        assertThat(last.sessionAnomaly()).isTrue();
        assertThat(last.triggeredGuardrailIds()).contains(VelocityGuard.SESSION_ANOMALY_TRIGGER_ID);
        // The session-anomaly threshold is >= the rate limit, so the ASK floor also holds.
        assertThat(last.floor().ordinal()).isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
    }

    @Property(tries = 200)
    void atOrBelowSessionAnomalyThresholdDoesNotFlagAnomaly(
            @ForAll @IntRange(min = 1, max = 8) int rateLimit,
            @ForAll @IntRange(min = 0, max = 8) int gap) {

        int sessionThreshold = rateLimit + gap;
        VelocityConfig cfg = new VelocityConfig(rateLimit, WINDOW_MS, 5_000L, 0.85, sessionThreshold);
        MutableClock clock = new MutableClock(1_000L);
        VelocityGuard guard = new VelocityGuard(cfg, clock);

        VelocityResult last = null;
        for (int i = 0; i < sessionThreshold; i++) { // count == threshold, not strictly above
            last = guard.evaluate(event("alice", "ls"));
        }

        assertThat(last).isNotNull();
        assertThat(last.sessionAnomaly()).isFalse();
        assertThat(last.triggeredGuardrailIds()).doesNotContain(VelocityGuard.SESSION_ANOMALY_TRIGGER_ID);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static CommandEvent event(String userId, String commandText) {
        return new CommandEvent(
                "evt-" + userId,
                Actor.human(userId),
                null,
                commandText,
                "/repo",
                "repo",
                Map.of(),
                1_000L,
                null,
                null,
                null,
                null);
    }

    /** A deterministic, manually-advanced clock so arrival times are reproducible in tests. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
