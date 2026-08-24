package com.intentguard.llm;

import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses and clamps the Gemini semantic-scoring response. Gemini is instructed to return only a
 * JSON object {@code {"semantic_inconsistency": <0.0-1.0>, "rationale": "<short>"}}, but real
 * model output is untrusted: it may be wrapped in prose or code fences, out of range, or malformed.
 *
 * <p>This logic is kept as a pure, side-effect-free class so it can be unit-tested without any
 * network or SDK dependency. Malformed output is treated as an error and yields
 * {@link OptionalDouble#empty()} (the component is then excluded upstream, Req 6.4). A parseable
 * score outside {@code [0.0, 1.0]} is clamped into range rather than rejected.
 */
public final class LlmResponseParser {

    /** JSON field carrying the semantic-inconsistency score. */
    static final String SCORE_FIELD = "semantic_inconsistency";

    /** JSON field carrying the model's short rationale. */
    static final String RATIONALE_FIELD = "rationale";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmResponseParser() {
    }

    /**
     * Extracts and clamps the {@code semantic_inconsistency} score from raw model output.
     *
     * @param raw the raw text returned by the model (may be {@code null}, or wrapped in prose)
     * @return the score clamped to {@code [0.0, 1.0]}, or {@link OptionalDouble#empty()} when the
     *         output is missing, non-numeric, not-a-number/infinite, or otherwise malformed
     */
    public static OptionalDouble parseSemanticScore(String raw) {
        JsonNode root = readJsonObject(raw);
        if (root == null) {
            return OptionalDouble.empty();
        }
        JsonNode scoreNode = root.get(SCORE_FIELD);
        if (scoreNode == null) {
            return OptionalDouble.empty();
        }
        double value;
        if (scoreNode.isNumber()) {
            value = scoreNode.asDouble();
        } else if (scoreNode.isTextual()) {
            try {
                value = Double.parseDouble(scoreNode.asText().trim());
            } catch (NumberFormatException notANumber) {
                return OptionalDouble.empty();
            }
        } else {
            return OptionalDouble.empty();
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(clamp(value));
    }

    /**
     * Extracts the optional {@code rationale} string from raw model output, if present.
     *
     * @param raw the raw text returned by the model
     * @return the trimmed rationale, or {@code null} when absent/malformed
     */
    public static String parseRationale(String raw) {
        JsonNode root = readJsonObject(raw);
        if (root == null) {
            return null;
        }
        JsonNode node = root.get(RATIONALE_FIELD);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Reads the first JSON object found in {@code raw}. Tolerates prose or code fences surrounding
     * the object by slicing from the first {@code &#123;} to the last {@code &#125;}; returns
     * {@code null} when no object-shaped content parses.
     */
    private static JsonNode readJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        String json = candidate.substring(start, end + 1);
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return null;
        }
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
