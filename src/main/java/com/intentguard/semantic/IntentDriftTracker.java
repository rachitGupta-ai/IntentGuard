package com.intentguard.semantic;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Per-session cumulative {@code IntentDrift} tracker (Req 8.3, 8.4), enabled only when
 * {@code intentguard.guardrails.semantic.enabled=true}.
 *
 * <p>Unlike a single Command_Event's Divergence_Score, {@code IntentDrift} accumulates each event's
 * divergence-from-intent contribution over an Intent_Session's lifetime. When a session's
 * cumulative drift exceeds the configured {@link SemanticGuardConfig#driftThreshold()}, the tracker
 * raises a session-level drift alert (Req 8.3) and records it (Req 8.4).
 *
 * <p>State (per-session cumulative drift) and the {@link Clock} are injectable so drift accumulation
 * and alert timing are deterministic in tests. The tracker is thread-safe.
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.semantic.enabled", havingValue = "true")
public class IntentDriftTracker {

    private final Clock clock;
    private final ConcurrentMap<String, Double> cumulativeBySession = new ConcurrentHashMap<>();

    /** Production constructor: accumulates against a UTC system clock. */
    public IntentDriftTracker() {
        this(Clock.systemUTC());
    }

    /** Test constructor: accumulates against the supplied (typically fixed) clock. */
    public IntentDriftTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Adds one event's divergence-from-intent contribution to its session's cumulative drift and
     * reports whether the session now exceeds the configured drift threshold.
     *
     * <p>Negative contributions are clamped to {@code 0.0} so drift is monotonically non-decreasing
     * over a session and a single benign event can never mask prior drift.
     *
     * @param sessionId    the Intent_Session accumulating drift, must not be {@code null}
     * @param eventDrift   this event's divergence-from-intent contribution
     * @param cfg          the active semantic-guard configuration, must not be {@code null}
     * @return an {@link IntentDriftResult} with the new cumulative drift and whether a session-level
     *         drift alert was raised and recorded (Req 8.3, 8.4)
     */
    public IntentDriftResult record(String sessionId, double eventDrift, SemanticGuardConfig cfg) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(cfg, "cfg must not be null");

        double contribution = eventDrift > 0.0 ? eventDrift : 0.0;
        double cumulative = cumulativeBySession.merge(sessionId, contribution, Double::sum);

        boolean alert = cumulative > cfg.driftThreshold();
        // An alert is recorded in the Audit_History exactly when it is raised (Req 8.4).
        return new IntentDriftResult(sessionId, cumulative, alert, alert, clock.millis());
    }

    /**
     * Returns the current cumulative {@code IntentDrift} for a session, or {@code 0.0} when the
     * session has not accumulated any drift yet.
     */
    public double cumulativeDrift(String sessionId) {
        return cumulativeBySession.getOrDefault(sessionId, 0.0);
    }

    /** Clears the accumulated drift for a session (for example when the Intent_Session closes). */
    public void reset(String sessionId) {
        cumulativeBySession.remove(sessionId);
    }
}
