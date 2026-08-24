package com.intentguard.scoring;

import java.util.List;
import java.util.Objects;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.ScoringContext;

/**
 * Context_Mismatch component (Req 5.3): how inconsistent the command is with its working-directory,
 * repository, and environment context, expressed as a value in [0,1].
 *
 * <h2>Heuristic</h2>
 * <p>The command is reduced to a {@link CommandNormalizer#category(String) category} (e.g.
 * {@code vcs}, {@code network}). The current situation is reduced to one or more coarse context
 * tags derived deterministically from {@code repo}, {@code cwd}, and environment (see
 * {@link #contextTags}). The profile's {@code contextAssociations} record which context tags each
 * category has previously appeared in. The score is then:
 *
 * <ul>
 *   <li><b>0.0 (consistent)</b> — the category has been seen in at least one of the current context
 *       tags.</li>
 *   <li><b>1.0 (inconsistent)</b> — the category is associated with some contexts in the profile,
 *       but none of the current tags: the command appears in a context it has never been seen in
 *       (e.g. a credential-read or package-publish in an unrelated directory).</li>
 *   <li><b>{@value #UNKNOWN_CATEGORY_SCORE} (unknown category)</b> — the profile has learned context
 *       associations, but none for this category: mildly surprising, neither clearly consistent nor
 *       clearly inconsistent.</li>
 *   <li><b>0.0 (no baseline)</b> — the profile has no context associations at all (e.g. a new
 *       profile): there is no basis to flag a mismatch, so the conservative least-divergent value is
 *       used rather than manufacturing risk.</li>
 * </ul>
 *
 * <p>The mapping reads only its inputs, so it is deterministic, and every branch lies in [0,1].
 */
public final class ContextMismatchComponent implements DivergenceComponent {

    /** Score for a category that carries no learned context association in a non-empty profile. */
    static final double UNKNOWN_CATEGORY_SCORE = 0.5;

    private final ProfileSnapshotProvider profileProvider;

    public ContextMismatchComponent(ProfileSnapshotProvider profileProvider) {
        this.profileProvider = Objects.requireNonNull(profileProvider, "profileProvider must not be null");
    }

    @Override
    public ComponentId id() {
        return ComponentId.CONTEXT_MISMATCH;
    }

    @Override
    public ComponentResult score(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        ProfileSnapshot profile = profileProvider.snapshotOrEmpty(ctx);
        CommandEvent event = ctx.event();

        String category = CommandNormalizer.category(event.commandText());
        List<String> currentTags = contextTags(event);

        double score;
        String note;
        if (profile.contextAssociations().isEmpty()) {
            // No learned associations at all: nothing to contradict, so no mismatch.
            score = 0.0;
            note = "no learned context associations";
        } else {
            List<String> expected = profile.contextAssociations().get(category);
            if (expected == null || expected.isEmpty()) {
                score = UNKNOWN_CATEGORY_SCORE;
                note = "category '" + category + "' has no learned context association";
            } else if (expected.stream().anyMatch(currentTags::contains)) {
                score = 0.0;
                note = "category '" + category + "' consistent with context " + currentTags;
            } else {
                score = 1.0;
                note = "category '" + category + "' unseen in context " + currentTags
                        + " (expected one of " + expected + ")";
            }
        }

        double weight = ctx.config().weightFor(id());
        return ComponentResult.scored(id(), clampUnit(score), weight, note);
    }

    /**
     * Derive coarse, deterministic context tags from the event's repo/cwd/env. A command in a
     * repository is tagged {@code repoDir}; otherwise the cwd's top-level location is classified.
     * Always returns at least one tag.
     */
    static List<String> contextTags(CommandEvent event) {
        String repo = event.repo();
        if (repo != null && !repo.isBlank()) {
            return List.of("repoDir");
        }
        String cwd = event.cwd() == null ? "" : event.cwd();
        if (cwd.startsWith("/home") || cwd.startsWith("/Users") || cwd.startsWith("~")) {
            return List.of("home");
        }
        if (cwd.startsWith("/tmp") || cwd.startsWith("/var/tmp")) {
            return List.of("tmp");
        }
        if (cwd.startsWith("/etc") || cwd.startsWith("/usr") || cwd.startsWith("/bin")
                || cwd.startsWith("/sbin") || cwd.startsWith("/var") || cwd.startsWith("/opt")) {
            return List.of("system");
        }
        return List.of("other");
    }

    private static double clampUnit(double value) {
        if (Double.isNaN(value)) {
            return 1.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
