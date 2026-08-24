package com.intentguard.scoring;

import java.util.Objects;
import java.util.Optional;

import com.intentguard.domain.ScoringContext;

/**
 * Supplies the {@link ProfileSnapshot} for a {@link ScoringContext}. This is the injection seam the
 * deterministic scoring components use to obtain profile data while {@code ScoringContext} does not
 * yet carry the Behavioral_Profile directly (see {@link ProfileSnapshot} for the rationale).
 *
 * <p>Each deterministic component takes a {@code ProfileSnapshotProvider} in its constructor.
 * Today it is typically backed by {@link #empty()} or a fixed snapshot (in tests); task&nbsp;13.1
 * will supply a provider that looks up the current user's persisted profile
 * ({@code BehavioralProfileDocument}) and adapts it into a {@link ProfileSnapshot}.
 */
@FunctionalInterface
public interface ProfileSnapshotProvider {

    /**
     * Return the profile snapshot to score {@code ctx} against, or {@link Optional#empty()} when no
     * profile is available (e.g. a brand-new user). Implementations MUST be deterministic and side
     * effect free so scoring stays reproducible.
     *
     * @param ctx the scoring context (event, intent, config); never {@code null}
     * @return the profile snapshot, or empty when unavailable
     */
    Optional<ProfileSnapshot> snapshotFor(ScoringContext ctx);

    /** A provider that never has a profile; components fall back to their no-profile behavior. */
    static ProfileSnapshotProvider empty() {
        return ctx -> Optional.empty();
    }

    /** A provider that always returns the same snapshot (useful for tests and scenario replays). */
    static ProfileSnapshotProvider fixed(ProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return ctx -> Optional.of(snapshot);
    }

    /**
     * Resolve the snapshot for {@code ctx}, substituting {@link ProfileSnapshot#empty()} when this
     * provider returns empty. Components call this so they always have a non-null snapshot to read.
     */
    default ProfileSnapshot snapshotOrEmpty(ScoringContext ctx) {
        return snapshotFor(ctx).orElseGet(ProfileSnapshot::empty);
    }
}
