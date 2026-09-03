package com.intentguard.api;

import java.util.List;

/**
 * Aggregated command-risk statistics for a single user, powering the "average command score" badge
 * and the 30-day risk-trend graph on the User_Profiling_Screen.
 *
 * <p>This is a read-only projection computed entirely from persisted {@code audit_history} command
 * decisions over a fixed trailing 30-day window (independent of the operator-selected display
 * window). It reveals nothing beyond values already stored (Req 9.5) and performs no writes
 * (Req 9.3).
 *
 * <p>{@link #present} is {@code false} when the user has no scored commands in the trailing 30
 * days; in that case the numeric fields are zero and {@link #daily} is an empty list, and the UI
 * shows an empty state rather than a misleading 0.00 average.
 *
 * @param present       whether any scored command exists in the trailing 30-day window
 * @param averageScore  mean divergence score across all commands in the window, in [0, 1]
 * @param commandCount  total number of scored commands in the window
 * @param allowCount    number of commands whose corrective action was ALLOW
 * @param askCount      number of commands whose corrective action was ASK
 * @param blockCount    number of commands whose corrective action was BLOCK
 * @param riskBand      human-readable band derived from {@link #averageScore}: LOW, ELEVATED, or HIGH
 * @param windowDays    the trailing window length in days (always 30)
 * @param daily         per-day points, oldest-first, one entry per day across the window
 */
public record RiskStats(
        boolean present,
        double averageScore,
        int commandCount,
        int allowCount,
        int askCount,
        int blockCount,
        String riskBand,
        int windowDays,
        List<DailyRiskPoint> daily) {

    /** Trailing window used for the risk trend, in days. */
    public static final int WINDOW_DAYS = 30;

    /**
     * Returns an "absent" RiskStats for a user with no scored commands in the trailing window.
     *
     * @param daily the (empty-count) daily series spanning the window, so the graph still renders
     *              a continuous axis; never {@code null}
     * @return a RiskStats with {@code present = false} and zeroed aggregates
     */
    public static RiskStats absent(List<DailyRiskPoint> daily) {
        return new RiskStats(false, 0.0, 0, 0, 0, 0, "NONE", WINDOW_DAYS,
                daily == null ? List.of() : List.copyOf(daily));
    }

    /**
     * Maps a mean divergence score to a coarse risk band for display.
     * LOW &lt; 0.4, ELEVATED &lt; 0.8, HIGH otherwise — aligned with the default ask/block thresholds.
     *
     * @param averageScore mean score in [0, 1]
     * @return one of {@code "LOW"}, {@code "ELEVATED"}, {@code "HIGH"}
     */
    public static String bandFor(double averageScore) {
        if (averageScore < 0.4) {
            return "LOW";
        }
        if (averageScore < 0.8) {
            return "ELEVATED";
        }
        return "HIGH";
    }
}
