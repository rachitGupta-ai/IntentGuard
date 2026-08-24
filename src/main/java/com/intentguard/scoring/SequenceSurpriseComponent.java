package com.intentguard.scoring;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.ScoringContext;

/**
 * Sequence_Surprise component (Req 5.2): the statistical unexpectedness of the observed command
 * given the user's own command history, expressed as a value in [0,1].
 *
 * <h2>Model</h2>
 * <p>The command is reduced to a {@link CommandNormalizer#normalizedToken normalized token}
 * (executable + coarse argument shape). Surprise is the normalized negative log-probability of the
 * observed transition under the profile's learned statistics, with add-one (Laplace) smoothing so
 * that never-before-seen transitions map toward 1.0:
 *
 * <ul>
 *   <li><b>Bigram transition</b> — when the profile knows the previous command token
 *       ({@link ProfileSnapshot#lastCommandToken()}), the probability is
 *       {@code P(curr | prev)} estimated from the {@code "prev>curr"} entries of
 *       {@code sequenceStats}.</li>
 *   <li><b>Unigram fallback</b> — otherwise the probability is {@code P(exec)} estimated from the
 *       {@code vocabulary} counts.</li>
 * </ul>
 *
 * <p>Let {@code C} be the observed count (transition or vocabulary), {@code D} the total count of
 * the relevant distribution, and {@code V} the vocabulary size (at least 1). With add-one
 * smoothing:
 * <pre>{@code
 *   p        = (C + 1) / (D + V + 1)
 *   pFloor   = (0 + 1) / (D + V + 1)          // probability mass of an unseen token
 *   surprise = -ln(p) / -ln(pFloor)           // in [0,1]: 1.0 for unseen, ->0 for frequent
 * }</pre>
 * The ratio is 1.0 exactly when {@code C == 0} (the transition/token was never seen) and shrinks
 * toward 0 as the observed count dominates. An entirely empty profile therefore yields 1.0 (every
 * command is novel), which the decision layer's learning clamp handles. The result is clamped to
 * [0,1] to guard against floating-point overshoot, and reads only its inputs so it is deterministic.
 */
public final class SequenceSurpriseComponent implements DivergenceComponent {

    private final ProfileSnapshotProvider profileProvider;

    public SequenceSurpriseComponent(ProfileSnapshotProvider profileProvider) {
        this.profileProvider = Objects.requireNonNull(profileProvider, "profileProvider must not be null");
    }

    @Override
    public ComponentId id() {
        return ComponentId.SEQUENCE_SURPRISE;
    }

    @Override
    public ComponentResult score(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        ProfileSnapshot profile = profileProvider.snapshotOrEmpty(ctx);

        String commandText = ctx.event().commandText();
        String token = CommandNormalizer.normalizedToken(commandText);
        String exec = CommandNormalizer.executable(commandText);

        double surprise = surprise(profile, token, exec);
        double weight = ctx.config().weightFor(id());
        String note = "surprise for token '" + token + "'";
        return ComponentResult.scored(id(), clampUnit(surprise), weight, note);
    }

    private static double surprise(ProfileSnapshot profile, String token, String exec) {
        int vocabularySize = Math.max(profile.vocabulary().size(), 1);

        Optional<String> prev = profile.lastCommandToken();
        if (prev.isPresent() && hasOutgoing(profile.sequenceStats(), prev.get())) {
            String prevToken = prev.get();
            long outgoingTotal = outgoingTotal(profile.sequenceStats(), prevToken);
            long transitionCount = value(profile.sequenceStats(), prevToken + ">" + token);
            return smoothedSurprise(transitionCount, outgoingTotal, vocabularySize);
        }

        // Unigram fallback over the vocabulary.
        long total = totalCounts(profile.vocabulary());
        long count = value(profile.vocabulary(), exec);
        return smoothedSurprise(count, total, vocabularySize);
    }

    /**
     * Normalized negative log-probability with add-one smoothing. Returns a value in [0,1] that is
     * 1.0 when {@code count == 0} (unseen) and approaches 0 as {@code count} dominates {@code total}.
     */
    private static double smoothedSurprise(long count, long total, int vocabularySize) {
        double denominator = total + vocabularySize + 1.0;
        double p = (count + 1.0) / denominator;
        double pFloor = 1.0 / denominator;
        double maxSurprise = -Math.log(pFloor);
        if (maxSurprise <= 0.0) {
            // Degenerate distribution (no discriminating information): treat as maximally novel.
            return 1.0;
        }
        double surprise = -Math.log(p);
        return surprise / maxSurprise;
    }

    private static boolean hasOutgoing(Map<String, Integer> sequenceStats, String prevToken) {
        String prefix = prevToken + ">";
        return sequenceStats.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    private static long outgoingTotal(Map<String, Integer> sequenceStats, String prevToken) {
        String prefix = prevToken + ">";
        long sum = 0L;
        for (Map.Entry<String, Integer> e : sequenceStats.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue() != null) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    private static long totalCounts(Map<String, Integer> counts) {
        long sum = 0L;
        for (Integer v : counts.values()) {
            if (v != null) {
                sum += v;
            }
        }
        return sum;
    }

    private static long value(Map<String, Integer> counts, String key) {
        Integer v = counts.get(key);
        return v == null ? 0L : v;
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
