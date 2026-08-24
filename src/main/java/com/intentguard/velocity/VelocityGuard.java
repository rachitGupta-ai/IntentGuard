package com.intentguard.velocity;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Stretch velocity/rate guardrail (Req 5), enabled only when
 * {@code intentguard.guardrails.velocity.enabled=true}. When the flag is absent the bean is not
 * created and the core decision chain is entirely unaffected.
 *
 * <p>The guard is <em>self-contained</em>: {@link #evaluate(CommandEvent)} /
 * {@link #evaluate(CommandEvent, long)} return a {@link VelocityResult} describing this guard's
 * contribution (a Corrective_Action floor, an optional Divergence_Score floor, a session-anomaly
 * flag, and the triggered guardrail ids). It does not read or mutate the shared
 * {@code GuardrailContext} / {@code GuardrailDecisionEngine}; a caller that wants to compose it into
 * the chain can fold {@link VelocityResult#floor()} in via {@code CorrectiveAction.max} and feed
 * {@link VelocityResult#scoreFloor()} into the threshold map.
 *
 * <h2>Per-actor rate window (Req 5.1, 5.3)</h2>
 * <p>For each Actor the guard keeps the arrival timestamps (taken from the injected {@link Clock})
 * of recent evaluations, evicting any older than {@link VelocityConfig#rateWindowMs()}. When the
 * resulting count strictly exceeds {@link VelocityConfig#rateLimit()} the floor is raised to
 * {@code ASK} (Req 5.1); when it strictly exceeds
 * {@link VelocityConfig#sessionAnomalyRateThreshold()} a session-anomaly alert is also signalled
 * (Req 5.3). Because the threshold is {@code >= rateLimit}, a session anomaly always implies the
 * {@code ASK} floor.
 *
 * <h2>Burst detection (Req 5.2)</h2>
 * <p>The inter-command interval is the gap between this evaluation's arrival and the actor's
 * previous arrival. When a Behavioral_Profile mean inter-command interval is supplied (a
 * non-negative {@code meanInterCommandMs}) and the interval's absolute deviation from that mean
 * exceeds {@link VelocityConfig#burstThresholdMs()}, the burst-anomaly Divergence_Score floor
 * ({@link VelocityConfig#burstAnomalyFloor()}) is raised. The single-argument overload supplies an
 * unknown mean, which disables burst detection for that call.
 *
 * <h2>Determinism &amp; concurrency</h2>
 * <p>An injected {@link Clock} makes arrival times reproducible in tests. Each actor's observe cycle
 * is guarded by a per-actor lock so the evict-count-record sequence is atomic across threads.
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.velocity.enabled", havingValue = "true")
public class VelocityGuard {

    /** Trigger id recorded when the windowed rate exceeds the rate limit (Req 5.1). */
    public static final String RATE_LIMIT_TRIGGER_ID = "velocity-rate-limit";

    /** Trigger id recorded when an inter-command burst raises the score floor (Req 5.2). */
    public static final String BURST_TRIGGER_ID = "velocity-burst-anomaly";

    /** Trigger id recorded when the windowed rate raises a session-anomaly alert (Req 5.3). */
    public static final String SESSION_ANOMALY_TRIGGER_ID = "velocity-session-anomaly";

    /** Sentinel meaning "no Behavioral_Profile mean available", which disables burst detection. */
    public static final long UNKNOWN_MEAN = -1L;

    private final VelocityConfig config;
    private final Clock clock;

    private final ConcurrentHashMap<String, Deque<Long>> arrivalsByActor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastArrivalByActor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> actorLocks = new ConcurrentHashMap<>();

    /** Spring entry point: builds the guard with {@link VelocityConfig#defaults()} and a UTC clock. */
    @Autowired
    public VelocityGuard() {
        this(VelocityConfig.defaults(), Clock.systemUTC());
    }

    /**
     * Builds the guard with an explicit configuration and clock (used by tests and scenario replays).
     *
     * @param config the velocity configuration, must not be {@code null}
     * @param clock  the clock used to stamp arrival times, must not be {@code null}
     */
    public VelocityGuard(VelocityConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Evaluates the velocity guardrails for {@code event} without a known Behavioral_Profile mean
     * inter-command interval, so burst detection is disabled for this call (Req 5.1, 5.3).
     *
     * @param event the Command_Event under evaluation, must not be {@code null}
     * @return this guard's {@link VelocityResult} contribution
     */
    public VelocityResult evaluate(CommandEvent event) {
        return evaluate(event, UNKNOWN_MEAN);
    }

    /**
     * Evaluates the velocity guardrails for {@code event} against the actor's rate window and, when
     * a mean inter-command interval is supplied, its burst deviation (Req 5.1, 5.2, 5.3, 5.4).
     *
     * @param event             the Command_Event under evaluation, must not be {@code null}
     * @param meanInterCommandMs the Behavioral_Profile mean inter-command interval in milliseconds,
     *                          or a negative value / {@link #UNKNOWN_MEAN} to disable burst detection
     * @return this guard's {@link VelocityResult} contribution
     */
    public VelocityResult evaluate(CommandEvent event, long meanInterCommandMs) {
        Objects.requireNonNull(event, "event must not be null");
        String actorId = event.actor().userId();
        long now = clock.millis();

        CorrectiveAction floor = CorrectiveAction.ALLOW;
        OptionalDouble scoreFloor = OptionalDouble.empty();
        boolean sessionAnomaly = false;
        List<String> triggered = new ArrayList<>();

        synchronized (lockFor(actorId)) {
            Long previousArrival = lastArrivalByActor.get(actorId);

            // Per-actor rate window (Req 5.1, 5.3): evict stale arrivals, then count this one.
            Deque<Long> arrivals = arrivalsByActor.computeIfAbsent(actorId, key -> new ArrayDeque<>());
            long windowStart = now - config.rateWindowMs();
            while (!arrivals.isEmpty() && arrivals.peekFirst() < windowStart) {
                arrivals.removeFirst();
            }
            arrivals.addLast(now);
            int windowCount = arrivals.size();

            if (windowCount > config.rateLimit()) {
                floor = floor.raiseTo(CorrectiveAction.ASK);
                triggered.add(RATE_LIMIT_TRIGGER_ID);
            }
            if (windowCount > config.sessionAnomalyRateThreshold()) {
                sessionAnomaly = true;
                triggered.add(SESSION_ANOMALY_TRIGGER_ID);
            }

            // Burst detection (Req 5.2): deviation of the inter-command interval from the mean.
            if (previousArrival != null && meanInterCommandMs >= 0) {
                long interval = now - previousArrival;
                if (Math.abs(interval - meanInterCommandMs) > config.burstThresholdMs()) {
                    scoreFloor = OptionalDouble.of(config.burstAnomalyFloor());
                    triggered.add(BURST_TRIGGER_ID);
                }
            }

            lastArrivalByActor.put(actorId, now);
        }

        return new VelocityResult(floor, scoreFloor, sessionAnomaly, triggered);
    }

    /** The active velocity configuration. */
    public VelocityConfig config() {
        return config;
    }

    private Object lockFor(String actorId) {
        return actorLocks.computeIfAbsent(actorId, key -> new Object());
    }
}
