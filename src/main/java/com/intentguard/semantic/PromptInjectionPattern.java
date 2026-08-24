package com.intentguard.semantic;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single configured prompt-injection heuristic (Req 8.1, 8.2): a unique {@code id} paired with a
 * case-insensitive regular expression {@code pattern} evaluated against a Command_Event's command
 * context.
 *
 * <p>The pattern is compiled eagerly at construction so a malformed pattern is rejected up front
 * rather than silently failing to match at evaluation time. Matching is a substring
 * ({@link java.util.regex.Matcher#find()}) search so a pattern need not span the whole context.
 *
 * @param id      the stable identifier recorded in the Audit_History when this pattern matches;
 *                must be non-blank
 * @param pattern the case-insensitive regular expression; must be non-blank and compilable
 */
public record PromptInjectionPattern(String id, String pattern) {

    public PromptInjectionPattern {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("prompt-injection pattern id must not be blank");
        }
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("prompt-injection pattern must not be blank");
        }
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException(
                    "prompt-injection pattern is not a valid regular expression: " + pattern, invalid);
        }
    }

    /**
     * Returns {@code true} when this pattern matches anywhere within {@code context}.
     *
     * @param context the command context to test; a {@code null} context never matches
     * @return whether the pattern is found in the context
     */
    public boolean matches(String context) {
        if (context == null) {
            return false;
        }
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(context).find();
    }
}
