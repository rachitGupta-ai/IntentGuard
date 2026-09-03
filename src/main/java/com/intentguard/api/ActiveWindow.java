package com.intentguard.api;

/**
 * Represents the resolved time window used to filter activity records on the User Profiling Screen.
 *
 * <p>Two window modes exist (Req 7.1, 7.4, 7.5):
 * <ul>
 *   <li><strong>Day window</strong> — {@code [now - days * 86_400_000, now]}, where {@code days}
 *       is a validated integer in [1, 365] (Req 7.1, 7.2).</li>
 *   <li><strong>Full-history window</strong> — {@code [earliestRecord, now]}. When the user has
 *       no persisted records the window is <em>empty</em> ({@link #empty} = {@code true}) and
 *       the {@code start}/{@code end} carry sentinel values (Req 7.4, 7.5).</li>
 * </ul>
 *
 * <p>An empty window means that no records exist for the user; all categories will return empty
 * results and the profile response sets {@code windowEmpty = true} (Req 7.5).
 *
 * @param start epoch-milliseconds lower bound (inclusive)
 * @param end   epoch-milliseconds upper bound (inclusive)
 * @param empty {@code true} when a full-history window was requested but no persisted records
 *              were found for the user, indicating the effective window is undefined (Req 7.5)
 */
public record ActiveWindow(long start, long end, boolean empty) {

    /**
     * Constructs an empty sentinel window, used when full history is requested but no records
     * exist for the user (Req 7.5).
     *
     * @return an {@code ActiveWindow} with {@code empty = true} and sentinel timestamps
     */
    public static ActiveWindow emptyWindow() {
        return new ActiveWindow(Long.MAX_VALUE, Long.MIN_VALUE, true);
    }

    /**
     * Constructs a concrete window with the given bounds and {@code empty = false}.
     *
     * @param start epoch-ms lower bound (inclusive)
     * @param end   epoch-ms upper bound (inclusive)
     * @return an {@code ActiveWindow} covering {@code [start, end]}
     */
    public static ActiveWindow of(long start, long end) {
        return new ActiveWindow(start, end, false);
    }
}
