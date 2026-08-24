package com.intentguard.llm;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LlmResponseParser}: well-formed JSON parses and clamps, out-of-range values
 * clamp into {@code [0,1]}, malformed output yields empty, and prose/code-fence wrapping is
 * tolerated. No network or SDK is involved.
 */
class LlmResponseParserTest {

    @Test
    void parsesWellFormedScore() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": 0.73, \"rationale\": \"off intent\"}");
        assertTrue(score.isPresent());
        assertEquals(0.73, score.getAsDouble(), 1e-9);
    }

    @Test
    void clampsAboveOneToOne() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": 1.5}");
        assertTrue(score.isPresent());
        assertEquals(1.0, score.getAsDouble(), 1e-9);
    }

    @Test
    void clampsBelowZeroToZero() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": -0.4}");
        assertTrue(score.isPresent());
        assertEquals(0.0, score.getAsDouble(), 1e-9);
    }

    @Test
    void toleratesProseAroundJson() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "Sure, here is the result:\n{\"semantic_inconsistency\": 0.2, \"rationale\": \"ok\"}\nHope that helps!");
        assertTrue(score.isPresent());
        assertEquals(0.2, score.getAsDouble(), 1e-9);
    }

    @Test
    void toleratesMarkdownCodeFence() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "```json\n{\"semantic_inconsistency\": 0.55}\n```");
        assertTrue(score.isPresent());
        assertEquals(0.55, score.getAsDouble(), 1e-9);
    }

    @Test
    void parsesNumericStringScore() {
        OptionalDouble score = LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": \"0.9\"}");
        assertTrue(score.isPresent());
        assertEquals(0.9, score.getAsDouble(), 1e-9);
    }

    @Test
    void emptyOnMissingField() {
        assertFalse(LlmResponseParser.parseSemanticScore("{\"rationale\": \"no score here\"}").isPresent());
    }

    @Test
    void emptyOnNonNumericScore() {
        assertFalse(LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": \"high\"}").isPresent());
    }

    @Test
    void emptyOnNaN() {
        assertFalse(LlmResponseParser.parseSemanticScore(
                "{\"semantic_inconsistency\": NaN}").isPresent());
    }

    @Test
    void emptyOnMalformedJson() {
        assertFalse(LlmResponseParser.parseSemanticScore("not json at all").isPresent());
        assertFalse(LlmResponseParser.parseSemanticScore("{oops").isPresent());
        assertFalse(LlmResponseParser.parseSemanticScore("").isPresent());
    }

    @Test
    void emptyOnNull() {
        assertFalse(LlmResponseParser.parseSemanticScore(null).isPresent());
    }

    @Test
    void parsesRationaleWhenPresent() {
        assertEquals("looks off",
                LlmResponseParser.parseRationale(
                        "{\"semantic_inconsistency\": 0.8, \"rationale\": \"looks off\"}"));
    }

    @Test
    void rationaleNullWhenAbsentOrMalformed() {
        assertNull(LlmResponseParser.parseRationale("{\"semantic_inconsistency\": 0.8}"));
        assertNull(LlmResponseParser.parseRationale("garbage"));
    }
}
