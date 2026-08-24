package com.intentguard.blastradius;

/**
 * The estimated impact of a single Command_Event, measured as the count of items the event would
 * affect (files matched by a recursive delete or wildcard, rows affected by a bulk update, etc.)
 * (Req 3.5).
 *
 * <p>When the guard cannot estimate the impact it produces an {@link #unknown()} value (its
 * {@link #indeterminate()} accessor is {@code true}), which drives the fail-safe {@code ASK} floor
 * (Req 3.8).
 *
 * <p>Note: the fail-safe factory is named {@link #unknown()} rather than {@code indeterminate()}
 * because a Java record cannot declare a static method with the same signature as the
 * {@code indeterminate()} accessor generated for the {@code indeterminate} component.
 *
 * @param affectedCount the estimated number of affected items; {@code 0} when indeterminate
 * @param indeterminate {@code true} when the blast radius could not be determined
 */
public record BlastRadius(int affectedCount, boolean indeterminate) {

    /** An unknown blast radius, used when the impact of a Command_Event cannot be estimated. */
    public static BlastRadius unknown() {
        return new BlastRadius(0, true);
    }
}
