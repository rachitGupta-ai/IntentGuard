package com.intentguard.scoring;

import java.util.Objects;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.ScoringContext;

/**
 * Behavioral_Deviation component (Req 5.4, Req 9): a weighted feature distance between the
 * Command_Event and the user's Behavioral_Profile, expressed as a value in [0,1].
 *
 * <h2>Feature distances (each in [0,1])</h2>
 * <ul>
 *   <li><b>Vocabulary membership</b> — 0 when the executable is in the profile vocabulary, 1 when
 *       it has never been seen.</li>
 *   <li><b>Sequencing</b> — 0 when the normalized token appears as a known successor in the
 *       profile's {@code sequenceStats}, 1 otherwise.</li>
 *   <li><b>Typed-vs-pasted</b> — 0 for a typed command; a mild value for an unknown origin; and a
 *       larger value for a <em>pasted</em> command. The pasted value is amplified where the
 *       profile's typed-vs-pasted ratio for the command's category is low (Req 9.1, 9.2).</li>
 *   <li><b>Timing</b> — a placeholder feature that is currently neutral (0). The scoring context
 *       does not yet carry inter-command timing; task&nbsp;13.1 can populate it from the profile's
 *       timing patterns. Documented so the seam is explicit rather than silently dropped.</li>
 * </ul>
 *
 * <p>The overall deviation is the fixed-weight combination
 * {@code w_vocab*fVocab + w_seq*fSeq + w_paste*fPaste + w_timing*fTiming} with non-negative internal
 * weights summing to 1.0, so the result is a convex combination in [0,1]. The internal weights are
 * distinct from the <em>component</em> weight (applied by the pipeline) that is attached to the
 * {@link ComponentResult}.
 *
 * <h2>Pasted-origin guarantee (Req 9.1, 9.2)</h2>
 * <p>Because only the typed-vs-pasted feature depends on {@link InputOrigin} and its pasted value is
 * strictly greater than its typed value (and {@code w_paste > 0}), a PASTED event always yields a
 * strictly greater deviation than the otherwise-identical TYPED event. The pasted feature value is a
 * decreasing function of the category's typed-vs-pasted ratio, so the pasted increase is at least as
 * large in a category with a lower ratio.
 */
public final class BehavioralDeviationComponent implements DivergenceComponent {

    // Internal feature weights (sum to 1.0).
    static final double W_VOCAB = 0.30;
    static final double W_SEQUENCE = 0.25;
    static final double W_PASTE = 0.35;
    static final double W_TIMING = 0.10;

    // Typed-vs-pasted feature values.
    static final double TYPED_FEATURE = 0.0;
    static final double UNKNOWN_ORIGIN_FEATURE = 0.20;
    /** Minimum pasted feature value (when the category is fully typed-dominant, ratio = 1.0). */
    static final double PASTED_BASE = 0.40;
    /** Additional pasted feature mass contributed as the category ratio falls from 1.0 to 0.0. */
    static final double PASTED_RATIO_SPAN = 0.60;
    /** Ratio assumed for a category with no learned typed-vs-pasted ratio (typed-dominant). */
    static final double DEFAULT_RATIO = 1.0;

    private final ProfileSnapshotProvider profileProvider;

    public BehavioralDeviationComponent(ProfileSnapshotProvider profileProvider) {
        this.profileProvider = Objects.requireNonNull(profileProvider, "profileProvider must not be null");
    }

    @Override
    public ComponentId id() {
        return ComponentId.BEHAVIORAL_DEVIATION;
    }

    @Override
    public ComponentResult score(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        ProfileSnapshot profile = profileProvider.snapshotOrEmpty(ctx);
        CommandEvent event = ctx.event();

        String exec = CommandNormalizer.executable(event.commandText());
        String token = CommandNormalizer.normalizedToken(event.commandText());
        String category = CommandNormalizer.category(event.commandText());

        double fVocab = vocabularyDistance(profile, exec);
        double fSeq = sequencingDistance(profile, token);
        double fPaste = pasteDistance(profile, event.inputOrigin(), category);
        double fTiming = 0.0; // Neutral placeholder; see class Javadoc.

        double deviation = W_VOCAB * fVocab
                + W_SEQUENCE * fSeq
                + W_PASTE * fPaste
                + W_TIMING * fTiming;

        double weight = ctx.config().weightFor(id());
        String note = event.isPasted() ? "pasted; category '" + category + "'" : null;
        return ComponentResult.scored(id(), clampUnit(deviation), weight, note);
    }

    private static double vocabularyDistance(ProfileSnapshot profile, String exec) {
        Integer count = profile.vocabulary().get(exec);
        return (count != null && count > 0) ? 0.0 : 1.0;
    }

    private static double sequencingDistance(ProfileSnapshot profile, String token) {
        String successorSuffix = ">" + token;
        boolean known = profile.sequenceStats().entrySet().stream()
                .anyMatch(e -> e.getValue() != null && e.getValue() > 0 && e.getKey().endsWith(successorSuffix));
        return known ? 0.0 : 1.0;
    }

    /**
     * The typed-vs-pasted feature value. Typed -> 0; unknown -> a mild constant; pasted ->
     * {@code PASTED_BASE + (1 - ratio) * PASTED_RATIO_SPAN}, so a lower category ratio yields a
     * larger pasted value (and thus a larger increase over the typed baseline).
     */
    static double pasteDistance(ProfileSnapshot profile, InputOrigin origin, String category) {
        if (origin == InputOrigin.TYPED) {
            return TYPED_FEATURE;
        }
        if (origin == InputOrigin.UNKNOWN) {
            return UNKNOWN_ORIGIN_FEATURE;
        }
        // PASTED
        double ratio = profile.typedPastedRatioByCategory().getOrDefault(category, DEFAULT_RATIO);
        double clampedRatio = clampUnit(ratio);
        double value = PASTED_BASE + (1.0 - clampedRatio) * PASTED_RATIO_SPAN;
        return clampUnit(value);
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
