package com.intentguard.api;

/**
 * The status of a single category in a {@link UserProfileView}. Indicates whether the category
 * was successfully assembled or whether the aggregation failed (timeout or repository error).
 *
 * <ul>
 *   <li>{@link #OK} — the category was assembled successfully; {@link CategoryView#records()} may
 *       still be empty if the user has no activity in the requested window.</li>
 *   <li>{@link #UNAVAILABLE} — the aggregation for this category timed out or threw an exception;
 *       {@link CategoryView#records()} will be empty and sibling categories are unaffected.</li>
 * </ul>
 *
 * <p>Req 10.3: a category that exceeds the 5-second cutoff or throws is returned with status
 * {@code UNAVAILABLE}; other categories are unaffected.
 */
public enum CategoryStatus {
    /** Category assembled successfully. Records may be empty (no activity in window). */
    OK,
    /** Category failed to assemble within the 5-second budget. Records are empty. */
    UNAVAILABLE
}
