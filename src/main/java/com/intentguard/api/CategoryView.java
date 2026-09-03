package com.intentguard.api;

import java.util.List;

/**
 * A bounded, status-annotated view of one activity category within a {@link UserProfileView}.
 *
 * <p>The {@link #records()} list contains at most {@code RECORD_CAP} (500) entries — the most
 * recent ones within the Active_Window. When the in-window record count exceeds the cap,
 * {@link #truncated()} is {@code true} and {@link #totalAvailable()} reports the full pre-cap
 * count so the UI can indicate that not all records are shown.
 *
 * <p>When the category could not be assembled (timeout or repository error),
 * {@link #status()} is {@link CategoryStatus#UNAVAILABLE} and {@link #records()} is empty;
 * sibling categories in the same profile response are unaffected.
 *
 * @param <T>            the category-specific record type
 * @param status         {@link CategoryStatus#OK} on success; {@link CategoryStatus#UNAVAILABLE}
 *                       on timeout / error (Req 10.3)
 * @param records        the (possibly capped) ordered record list; never {@code null}; at most
 *                       {@code RECORD_CAP} entries (Req 8.1)
 * @param truncated      {@code true} when the in-window count exceeded the cap and only the most
 *                       recent {@code RECORD_CAP} records are returned (Req 8.2)
 * @param totalAvailable the full in-window count before capping; equals {@code records.size()}
 *                       when not truncated (Req 8.3)
 */
public record CategoryView<T>(
        CategoryStatus status,
        List<T> records,
        boolean truncated,
        int totalAvailable) {

    /**
     * Returns an {@code UNAVAILABLE} category view with an empty record list. Used when the
     * aggregation for this category timed out or threw an exception.
     *
     * <p>Req 10.3: a category that exceeds the 5-second cutoff or throws is returned as
     * {@code UNAVAILABLE} with empty records; sibling categories are unaffected.
     *
     * @param <T> the category-specific record type
     * @return an unavailable category view
     */
    public static <T> CategoryView<T> unavailable() {
        return new CategoryView<>(CategoryStatus.UNAVAILABLE, List.of(), false, 0);
    }

    /**
     * Returns an {@code OK} category view wrapping the already-capped record list.
     *
     * <p>The {@code capped} list must already contain at most {@code RECORD_CAP} records (the
     * most recent ones); the caller is responsible for the selection step. This factory makes a
     * defensive copy via {@link List#copyOf(java.util.Collection)} so the returned view is
     * immutable.
     *
     * <p>Req 8.1: records.size() ≤ RECORD_CAP.<br>
     * Req 8.2: {@code truncated} is {@code true} iff the pre-cap count exceeded the cap.<br>
     * Req 8.3: {@code totalAvailable} is the full in-window count before capping.
     *
     * @param <T>            the category-specific record type
     * @param capped         the post-cap, display-ordered record list
     * @param truncated      whether the pre-cap in-window count exceeded {@code RECORD_CAP}
     * @param totalAvailable the full in-window count before capping
     * @return an OK category view
     */
    public static <T> CategoryView<T> of(List<T> capped, boolean truncated, int totalAvailable) {
        return new CategoryView<>(CategoryStatus.OK, List.copyOf(capped), truncated, totalAvailable);
    }
}
