package com.intentguard.scoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A minimal, immutable, read-only view of the parts of a user's Behavioral_Profile that the
 * deterministic scoring components need (Req 5.2, 5.3, 5.4, 9.1, 9.2).
 *
 * <h2>Why this exists — the profile seam</h2>
 * <p>At the time task&nbsp;6.4 was implemented, {@code ScoringContext} does not yet carry the
 * user's Behavioral_Profile (task&nbsp;6.1 constructs the context with a {@code null} profile and a
 * fixed {@code ACTIVE} state; full profile wiring is task&nbsp;13.1). To keep the deterministic
 * components self-contained and unit-testable <em>now</em>, they read profile data through this
 * small read-only interface, obtained via a {@link ProfileSnapshotProvider} injected into each
 * component's constructor.
 *
 * <p>This mirrors, field-for-field, the persisted
 * {@code com.intentguard.persistence.BehavioralProfileDocument} ({@code vocabulary},
 * {@code sequenceStats}, {@code typedPastedRatioByCategory}, {@code contextAssociations}) plus the
 * {@code eventCount}. Task&nbsp;9.1/13.1 can back this view with the real document by supplying a
 * {@link ProfileSnapshotProvider} that adapts a {@code BehavioralProfileDocument} into a
 * {@code ProfileSnapshot} (via {@link Builder}) — no component code needs to change.
 *
 * <p>All accessors return non-null, unmodifiable collections. {@link #lastCommandToken()} is the
 * normalized token of the user's most recent (allowed) command, used by Sequence_Surprise to form
 * a bigram transition; it is {@link Optional#empty()} when unknown (e.g. first command, or history
 * not yet wired).
 */
public interface ProfileSnapshot {

    /** Number of Command_Events learned into this profile. */
    long eventCount();

    /** Command vocabulary keyed by executable (e.g. {@code git} -> 300). Never null. */
    Map<String, Integer> vocabulary();

    /** Bigram transition counts keyed {@code "prevToken>currToken"}. Never null. */
    Map<String, Integer> sequenceStats();

    /** Typed-vs-pasted ratio per command category (e.g. {@code vcs} -> 0.98). Never null. */
    Map<String, Double> typedPastedRatioByCategory();

    /** Context tags each command category has been observed in (e.g. {@code vcs} -> [repoDir]). */
    Map<String, List<String>> contextAssociations();

    /** The normalized token of the user's most recent command, when known. */
    Optional<String> lastCommandToken();

    /** An empty snapshot (no learned data), used when a profile is unavailable. */
    static ProfileSnapshot empty() {
        return builder().build();
    }

    static Builder builder() {
        return new Builder();
    }

    /** Fluent builder producing an immutable {@link ProfileSnapshot}. */
    final class Builder {
        private long eventCount;
        private Map<String, Integer> vocabulary = Map.of();
        private Map<String, Integer> sequenceStats = Map.of();
        private Map<String, Double> typedPastedRatioByCategory = Map.of();
        private Map<String, List<String>> contextAssociations = Map.of();
        private String lastCommandToken;

        private Builder() {
        }

        public Builder eventCount(long eventCount) {
            this.eventCount = eventCount;
            return this;
        }

        public Builder vocabulary(Map<String, Integer> vocabulary) {
            this.vocabulary = vocabulary == null ? Map.of() : Map.copyOf(vocabulary);
            return this;
        }

        public Builder sequenceStats(Map<String, Integer> sequenceStats) {
            this.sequenceStats = sequenceStats == null ? Map.of() : Map.copyOf(sequenceStats);
            return this;
        }

        public Builder typedPastedRatioByCategory(Map<String, Double> ratios) {
            this.typedPastedRatioByCategory = ratios == null ? Map.of() : Map.copyOf(ratios);
            return this;
        }

        public Builder contextAssociations(Map<String, List<String>> associations) {
            if (associations == null) {
                this.contextAssociations = Map.of();
            } else {
                Map<String, List<String>> copy = new LinkedHashMap<>();
                associations.forEach((k, v) -> copy.put(k, v == null ? List.of() : List.copyOf(v)));
                this.contextAssociations = Map.copyOf(copy);
            }
            return this;
        }

        public Builder lastCommandToken(String lastCommandToken) {
            this.lastCommandToken = lastCommandToken;
            return this;
        }

        public ProfileSnapshot build() {
            return new ImmutableProfileSnapshot(
                    eventCount,
                    vocabulary,
                    sequenceStats,
                    typedPastedRatioByCategory,
                    contextAssociations,
                    lastCommandToken);
        }
    }

    /** Immutable value implementation returned by {@link Builder#build()}. */
    final class ImmutableProfileSnapshot implements ProfileSnapshot {
        private final long eventCount;
        private final Map<String, Integer> vocabulary;
        private final Map<String, Integer> sequenceStats;
        private final Map<String, Double> typedPastedRatioByCategory;
        private final Map<String, List<String>> contextAssociations;
        private final String lastCommandToken;

        ImmutableProfileSnapshot(
                long eventCount,
                Map<String, Integer> vocabulary,
                Map<String, Integer> sequenceStats,
                Map<String, Double> typedPastedRatioByCategory,
                Map<String, List<String>> contextAssociations,
                String lastCommandToken) {
            this.eventCount = eventCount;
            this.vocabulary = Objects.requireNonNull(vocabulary);
            this.sequenceStats = Objects.requireNonNull(sequenceStats);
            this.typedPastedRatioByCategory = Objects.requireNonNull(typedPastedRatioByCategory);
            this.contextAssociations = Objects.requireNonNull(contextAssociations);
            this.lastCommandToken = lastCommandToken;
        }

        @Override
        public long eventCount() {
            return eventCount;
        }

        @Override
        public Map<String, Integer> vocabulary() {
            return vocabulary;
        }

        @Override
        public Map<String, Integer> sequenceStats() {
            return sequenceStats;
        }

        @Override
        public Map<String, Double> typedPastedRatioByCategory() {
            return typedPastedRatioByCategory;
        }

        @Override
        public Map<String, List<String>> contextAssociations() {
            return contextAssociations;
        }

        @Override
        public Optional<String> lastCommandToken() {
            return Optional.ofNullable(lastCommandToken);
        }
    }
}
