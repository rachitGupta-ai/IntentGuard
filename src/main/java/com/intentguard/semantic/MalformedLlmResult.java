package com.intentguard.semantic;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The contribution of the semantic LLM guard after evaluating an LLM response (Req 8.5, 8.6).
 *
 * <p>When the LLM response is malformed (or otherwise unusable), the semantic score is
 * {@link OptionalDouble#empty()} — the response is <strong>excluded</strong> from the
 * Divergence_Score and never treated as a signal — and {@code recordedError} carries the
 * malformed-response error for the Audit_History.
 *
 * @param score         the usable semantic-inconsistency score in {@code [0.0, 1.0]}, or
 *                      {@link OptionalDouble#empty()} when the response was malformed and excluded
 * @param malformed     whether the response was malformed and therefore excluded from the score
 * @param recordedError the malformed-response error to record, present exactly when
 *                      {@code malformed} is {@code true}
 */
public record MalformedLlmResult(
        OptionalDouble score, boolean malformed, String recordedError) {

    /** Error string recorded when a semantic-guardrail LLM response is malformed (Req 8.6). */
    public static final String MALFORMED_RESPONSE_ERROR = "semantic-llm-malformed-response";

    public MalformedLlmResult {
        Objects.requireNonNull(score, "score must not be null");
        if (malformed) {
            if (score.isPresent()) {
                throw new IllegalArgumentException(
                        "a malformed response must be excluded from the score");
            }
            if (recordedError == null) {
                throw new IllegalArgumentException(
                        "a malformed response must carry a recorded error");
            }
        }
    }

    /** A usable-score result: the response parsed cleanly and contributes {@code value}. */
    public static MalformedLlmResult usable(double value) {
        return new MalformedLlmResult(OptionalDouble.of(value), false, null);
    }

    /** A malformed result: the response is excluded from the score and the error is recorded. */
    public static MalformedLlmResult excluded() {
        return new MalformedLlmResult(OptionalDouble.empty(), true, MALFORMED_RESPONSE_ERROR);
    }

    /** Whether this response is excluded from the Divergence_Score (i.e. malformed). */
    public boolean excludedFromScore() {
        return score.isEmpty();
    }

    /** The recorded malformed-response error, if any, for the Audit_History (Req 8.6). */
    public Optional<String> error() {
        return Optional.ofNullable(recordedError);
    }
}
