package com.intentguard.semantic;

import java.util.List;
import java.util.Objects;

/**
 * Tunable configuration for the semantic / LLM guardrails (Req 8).
 *
 * @param promptInjectionPatterns the ordered set of configured prompt-injection heuristics; the
 *                                first pattern to match a command context wins (Req 8.1, 8.2)
 * @param promptInjectionFloor    the Divergence_Score floor a prompt-injection match raises the
 *                                event to, in {@code [0.0, 1.0]} (Req 8.1)
 * @param driftThreshold          the cumulative {@code IntentDrift} threshold above which a
 *                                session-level drift alert is raised; must be {@code >= 0.0}
 *                                (Req 8.3, 8.4)
 */
public record SemanticGuardConfig(
        List<PromptInjectionPattern> promptInjectionPatterns,
        double promptInjectionFloor,
        double driftThreshold) {

    /** Default prompt-injection Divergence_Score floor when none is configured. */
    public static final double DEFAULT_PROMPT_INJECTION_FLOOR = 0.85;

    /** Default cumulative-drift threshold when none is configured. */
    public static final double DEFAULT_DRIFT_THRESHOLD = 1.0;

    public SemanticGuardConfig {
        promptInjectionPatterns = promptInjectionPatterns == null
                ? List.of()
                : List.copyOf(promptInjectionPatterns);
        if (promptInjectionFloor < 0.0 || promptInjectionFloor > 1.0) {
            throw new IllegalArgumentException(
                    "promptInjectionFloor must be in [0.0, 1.0] but was " + promptInjectionFloor);
        }
        if (driftThreshold < 0.0 || Double.isNaN(driftThreshold)) {
            throw new IllegalArgumentException(
                    "driftThreshold must be >= 0.0 but was " + driftThreshold);
        }
        // Enforce unique pattern ids so a recorded id unambiguously identifies its pattern.
        long distinctIds = promptInjectionPatterns.stream()
                .map(PromptInjectionPattern::id)
                .distinct()
                .count();
        if (distinctIds != promptInjectionPatterns.size()) {
            throw new IllegalArgumentException("prompt-injection pattern ids must be unique");
        }
    }

    /** A default configuration: no patterns, standard floor and drift threshold. */
    public static SemanticGuardConfig defaults() {
        return new SemanticGuardConfig(
                List.of(), DEFAULT_PROMPT_INJECTION_FLOOR, DEFAULT_DRIFT_THRESHOLD);
    }

    /** A configuration with the given patterns and the default floor / drift threshold. */
    public static SemanticGuardConfig withPatterns(List<PromptInjectionPattern> patterns) {
        return new SemanticGuardConfig(
                Objects.requireNonNull(patterns, "patterns must not be null"),
                DEFAULT_PROMPT_INJECTION_FLOOR,
                DEFAULT_DRIFT_THRESHOLD);
    }
}
