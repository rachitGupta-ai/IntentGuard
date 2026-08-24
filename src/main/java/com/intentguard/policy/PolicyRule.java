package com.intentguard.policy;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.intentguard.domain.CommandEvent;

/**
 * One ordered entry in a {@link CommandPolicy} (Req 2.1).
 *
 * <p>A rule pairs a {@link PatternKind glob or regex} pattern with an optional {@link PolicyScope}
 * qualifier and a {@link PolicyAction}. It is valid by construction: the compact constructor
 * rejects a blank id, a null pattern kind, a blank or non-compilable pattern, a null scope, or a
 * null action by throwing {@link InvalidCommandPolicyException} (Req 2.12).
 *
 * <p>{@link #matches(CommandEvent, String)} applies the {@link PolicyScope} qualifier <em>and</em>
 * the pattern to the {@linkplain #normalize(CommandEvent) normalized} command text and arguments
 * (Req 2.4, 2.5). Glob patterns are matched anchored over the whole normalized command; regex
 * patterns are matched as an unanchored, case-insensitive search.
 *
 * @param id      unique, non-blank identifier of the rule within its policy
 * @param kind    how {@code pattern} is interpreted (glob or regex)
 * @param pattern the glob or regex pattern; non-blank and compilable
 * @param scope   the qualifier restricting the rule; never {@code null} ({@link PolicyScope#any()}
 *                when unscoped)
 * @param action  the enforcement action; never {@code null}
 */
public record PolicyRule(
        String id,
        PatternKind kind,
        String pattern,
        PolicyScope scope,
        PolicyAction action) {

    public PolicyRule {
        if (id == null || id.isBlank()) {
            throw new InvalidCommandPolicyException("PolicyRule id must not be blank");
        }
        if (kind == null) {
            throw new InvalidCommandPolicyException(
                    "PolicyRule '" + id + "' must have a pattern kind");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new InvalidCommandPolicyException(
                    "PolicyRule '" + id + "' must have a non-blank pattern");
        }
        if (scope == null) {
            throw new InvalidCommandPolicyException(
                    "PolicyRule '" + id + "' scope must not be null; use PolicyScope.any()");
        }
        if (action == null) {
            throw new InvalidCommandPolicyException(
                    "PolicyRule '" + id + "' must have an action");
        }
        // Fail fast on a pattern that cannot be compiled (Req 2.12).
        try {
            compile(kind, pattern);
        } catch (PatternSyntaxException e) {
            throw new InvalidCommandPolicyException(
                    "PolicyRule '" + id + "' has a non-compilable pattern: " + e.getMessage(), e);
        }
    }

    /**
     * Returns whether this rule applies to {@code event} evaluated under {@code group}: the
     * {@link PolicyScope} must apply <em>and</em> the pattern must match the normalized command
     * text and arguments (Req 2.4, 2.5).
     */
    public boolean matches(CommandEvent event, String group) {
        Objects.requireNonNull(event, "event must not be null");
        if (!scope.matches(event, group)) {
            return false;
        }
        String normalized = normalize(event);
        Pattern compiled = compile(kind, pattern);
        return kind == PatternKind.GLOB
                ? compiled.matcher(normalized).matches()
                : compiled.matcher(normalized).find();
    }

    /**
     * The command text and arguments reduced to a stable form for matching: leading/trailing
     * whitespace stripped, internal whitespace collapsed to single spaces, and lowercased so
     * identical commands always reduce to identical text (Req 2.4).
     */
    static String normalize(CommandEvent event) {
        return event.commandText().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Compiles the effective {@link Pattern} for a rule: a glob is first translated to an anchored
     * regex, a regex is compiled directly. Both are compiled case-insensitively to complement the
     * lowercased normalized command text. Throws {@link PatternSyntaxException} for an invalid
     * regex.
     */
    static Pattern compile(PatternKind kind, String pattern) {
        String regex = kind == PatternKind.GLOB ? globToRegex(pattern) : pattern;
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * Translates a shell-style glob to an equivalent regex: {@code *} becomes {@code .*},
     * {@code ?} becomes {@code .}, and every other regex metacharacter is escaped so it is treated
     * literally.
     */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
