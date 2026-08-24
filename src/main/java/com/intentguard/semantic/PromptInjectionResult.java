package com.intentguard.semantic;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The contribution of the prompt-injection guard for a single Command_Event (Req 8.1, 8.2).
 *
 * @param matched         whether the command context matched a configured prompt-injection pattern
 * @param matchedPatternId the id of the first matching pattern, recorded in the Audit_History, or
 *                        {@code null} when nothing matched
 * @param scoreFloor      the Divergence_Score floor to raise the event to on a match, fed to the
 *                        threshold map; {@link OptionalDouble#empty()} when nothing matched
 */
public record PromptInjectionResult(
        boolean matched, String matchedPatternId, OptionalDouble scoreFloor) {

    public PromptInjectionResult {
        Objects.requireNonNull(scoreFloor, "scoreFloor must not be null");
        if (matched && matchedPatternId == null) {
            throw new IllegalArgumentException("a matched result must carry a matched pattern id");
        }
    }

    /** A no-match result: no floor raised, no pattern recorded. */
    public static PromptInjectionResult none() {
        return new PromptInjectionResult(false, null, OptionalDouble.empty());
    }

    /** A match result raising {@code floor} and recording {@code patternId}. */
    public static PromptInjectionResult match(String patternId, double floor) {
        return new PromptInjectionResult(
                true,
                Objects.requireNonNull(patternId, "patternId must not be null"),
                OptionalDouble.of(floor));
    }

    /** The recorded matched-pattern id, if any, for the Audit_History (Req 8.2). */
    public Optional<String> recordedPatternId() {
        return Optional.ofNullable(matchedPatternId);
    }
}
