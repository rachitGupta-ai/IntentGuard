package com.intentguard.timecontext;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A configured recurring daily interval during which risky operations are permitted at a lower
 * Corrective_Action floor (Req 7.1). The window is expressed as a local-time range
 * {@code [start, end]} that is evaluated in the {@link TimeContextConfig#zone() configured time
 * zone}. Both the {@code start} and {@code end} instants are treated as <em>inclusive</em>: a
 * current time that maps to exactly {@code start} or exactly {@code end} is considered
 * <em>within</em> the window.
 *
 * <p>Windows that do not wrap past midnight ({@code start <= end}) cover the closed range
 * {@code [start, end]}. Windows that wrap past midnight ({@code start > end}, e.g. {@code 22:00}
 * to {@code 02:00}) cover {@code [start, 23:59:59.999999999] ∪ [00:00, end]}.
 *
 * @param start the inclusive start of the daily window in the configured zone, never {@code null}
 * @param end   the inclusive end of the daily window in the configured zone, never {@code null}
 */
public record MaintenanceWindow(LocalTime start, LocalTime end) {

    public MaintenanceWindow {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
    }

    /**
     * Returns whether the given local time falls within this window, treating both boundaries as
     * inclusive (Req 7.1).
     *
     * @param time the local time (already resolved in the configured zone), must not be {@code null}
     * @return {@code true} if {@code time} is within {@code [start, end]} inclusive
     */
    public boolean contains(LocalTime time) {
        Objects.requireNonNull(time, "time must not be null");
        if (!start.isAfter(end)) {
            // Non-wrapping window: start <= time <= end.
            return !time.isBefore(start) && !time.isAfter(end);
        }
        // Wrapping window (crosses midnight): time >= start OR time <= end.
        return !time.isBefore(start) || !time.isAfter(end);
    }
}
