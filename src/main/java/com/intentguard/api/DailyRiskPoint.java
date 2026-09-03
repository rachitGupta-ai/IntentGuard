package com.intentguard.api;

/**
 * One day's aggregated command-risk figure for the User_Profiling_Screen risk trend graph.
 *
 * <p>Each point summarises all of a user's {@code audit_history} command decisions that fall on a
 * single calendar day (UTC), giving the mean divergence score and how many commands contributed.
 * Days on which the user ran no commands are still emitted with {@code count = 0} and
 * {@code averageScore = 0.0} so the graph shows a continuous 30-day axis with visible gaps.
 *
 * @param date         the calendar day in {@code yyyy-MM-dd} form (UTC)
 * @param epochDayMs   epoch-millis at the start (00:00 UTC) of {@code date}, for client plotting
 * @param count        number of command decisions on that day (>= 0)
 * @param averageScore mean divergence score across the day's commands, in [0, 1]; 0.0 when empty
 */
public record DailyRiskPoint(String date, long epochDayMs, int count, double averageScore) {}
