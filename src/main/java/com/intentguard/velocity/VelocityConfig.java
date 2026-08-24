package com.intentguard.velocity;

/**
 * Configuration for the {@link VelocityGuard} (Req 5).
 *
 * @param rateLimit                   maximum number of Command_Events a single Actor may issue
 *                                    within {@link #rateWindowMs} before the guard raises the
 *                                    Corrective_Action floor to {@code ASK}; a count strictly
 *                                    greater than this raises the floor (Req 5.1). Must be {@code >= 1}.
 * @param rateWindowMs                width of the rolling rate-limit window in milliseconds; only
 *                                    arrivals within {@code [now - rateWindowMs, now]} are counted.
 *                                    Must be {@code >= 1}.
 * @param burstThresholdMs            maximum tolerated absolute deviation, in milliseconds, of an
 *                                    event's inter-command interval from the Behavioral_Profile mean
 *                                    inter-command interval; a deviation strictly greater than this
 *                                    raises the burst-anomaly Divergence_Score floor (Req 5.2). Must
 *                                    be {@code >= 0}.
 * @param burstAnomalyFloor           Divergence_Score floor, in {@code [0.0, 1.0]}, applied on a
 *                                    burst-anomaly (Req 5.2).
 * @param sessionAnomalyRateThreshold rate-window count strictly above which the guard signals a
 *                                    session-anomaly alert (Req 5.3). Must be {@code >= rateLimit}
 *                                    so the session-anomaly signal implies the rate-limit floor.
 */
public record VelocityConfig(
        int rateLimit,
        long rateWindowMs,
        long burstThresholdMs,
        double burstAnomalyFloor,
        int sessionAnomalyRateThreshold) {

    public VelocityConfig {
        if (rateLimit < 1) {
            throw new IllegalArgumentException("rateLimit must be >= 1: " + rateLimit);
        }
        if (rateWindowMs < 1) {
            throw new IllegalArgumentException("rateWindowMs must be >= 1: " + rateWindowMs);
        }
        if (burstThresholdMs < 0) {
            throw new IllegalArgumentException("burstThresholdMs must be >= 0: " + burstThresholdMs);
        }
        if (Double.isNaN(burstAnomalyFloor) || burstAnomalyFloor < 0.0 || burstAnomalyFloor > 1.0) {
            throw new IllegalArgumentException(
                    "burstAnomalyFloor must be in [0.0, 1.0]: " + burstAnomalyFloor);
        }
        if (sessionAnomalyRateThreshold < rateLimit) {
            throw new IllegalArgumentException(
                    "sessionAnomalyRateThreshold must be >= rateLimit: " + sessionAnomalyRateThreshold);
        }
    }

    /**
     * Sensible defaults for the prototype: at most 20 commands per 60s before an {@code ASK} floor,
     * a 5s inter-command deviation tolerance, a 0.85 burst-anomaly score floor, and a session
     * anomaly once the windowed rate exceeds 40.
     */
    public static VelocityConfig defaults() {
        return new VelocityConfig(20, 60_000L, 5_000L, 0.85, 40);
    }
}
