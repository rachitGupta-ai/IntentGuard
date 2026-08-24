package com.intentguard.policy;

/**
 * How a {@link PolicyRule}'s pattern string is interpreted when matched against the normalized
 * command text and arguments (Req 2.4).
 *
 * <ul>
 *   <li>{@code GLOB} - a shell-style glob where {@code *} matches any run of characters and
 *       {@code ?} matches any single character; matched against the whole normalized command
 *       (anchored).</li>
 *   <li>{@code REGEX} - a {@link java.util.regex.Pattern Java regular expression}; matched as an
 *       unanchored search over the normalized command.</li>
 * </ul>
 */
public enum PatternKind {
    GLOB,
    REGEX
}
