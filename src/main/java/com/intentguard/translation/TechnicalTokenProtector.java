package com.intentguard.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.intentguard.scoring.CommandNormalizer;

/**
 * Guarantees {@code Technical_Token} integrity independent of any Translation_Provider or
 * Speech_Provider behaviour (Req 2.3, 5.2, 7.1, 7.4). This is the single most important correctness
 * component of the translation layer: divergence scoring, the audit trail, and policy matching all
 * depend on command text and identifiers being reproduced byte-for-byte, so those substrings must
 * never be exposed to — or altered by — a machine-translation or speech-synthesis provider.
 *
 * <p>The protector works in three steps around a provider call:
 * <ol>
 *   <li>{@link #mask(String)} detects Technical_Tokens and replaces each with an opaque,
 *       translation-stable sentinel (for example {@code ⟦IG0⟧}) that providers pass through
 *       untouched, returning a {@link MaskedText}.</li>
 *   <li>the caller sends {@link MaskedText#masked()} to the provider, which rewrites only the
 *       surrounding prose;</li>
 *   <li>{@link #restore(String, MaskedText)} substitutes each sentinel back with the exact original
 *       bytes, and {@link #allTokensPreserved(String, MaskedText)} verifies every token survived so
 *       the {@code TranslationService} can fall back to the original Source_Text when it did not.</li>
 * </ol>
 *
 * <p>Token detection reuses the same normalization vocabulary as
 * {@link com.intentguard.scoring.CommandNormalizer} (its {@link CommandNormalizer#knownExecutables()
 * known executables}) so command recognition stays consistent with scoring. On top of that
 * vocabulary the protector recognizes command text and arguments, absolute/relative file paths, host
 * names and URLs, resource identifiers, code fragments, numeric scores, timestamps, and reason codes.
 *
 * <p>Instances are immutable and thread-safe (the compiled pattern is shared and stateless).
 */
public final class TechnicalTokenProtector {

    /** Opening bracket of a sentinel: MATHEMATICAL LEFT WHITE SQUARE BRACKET (U+27E6). */
    private static final char SENTINEL_OPEN = '\u27E6';
    /** Closing bracket of a sentinel: MATHEMATICAL RIGHT WHITE SQUARE BRACKET (U+27E7). */
    private static final char SENTINEL_CLOSE = '\u27E7';
    /** Marker inside a sentinel; chosen so it is unlikely to appear in translatable prose. */
    private static final String SENTINEL_MARK = "IG";

    private final Pattern tokenPattern;

    /**
     * Creates a protector using the default {@link CommandNormalizer#knownExecutables() executable
     * vocabulary} for command detection.
     */
    public TechnicalTokenProtector() {
        this.tokenPattern = buildTokenPattern(CommandNormalizer.knownExecutables());
    }

    /**
     * Detects Technical_Tokens in {@code sourceText} and replaces each with an opaque sentinel.
     *
     * @param sourceText the Source_Text to protect; may be {@code null} or blank
     * @return a {@link MaskedText} pairing the masked string with the removed tokens
     */
    public MaskedText mask(String sourceText) {
        if (sourceText == null || sourceText.isEmpty()) {
            return new MaskedText(sourceText == null ? "" : sourceText, List.of());
        }
        Matcher matcher = tokenPattern.matcher(sourceText);
        StringBuilder masked = new StringBuilder(sourceText.length());
        List<String> tokens = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                // Never mask a zero-width match; let the matcher advance to the next position.
                continue;
            }
            masked.append(sourceText, last, matcher.start());
            int index = tokens.size();
            tokens.add(matcher.group());
            masked.append(sentinel(index));
            last = matcher.end();
        }
        masked.append(sourceText, last, sourceText.length());
        return new MaskedText(masked.toString(), tokens);
    }

    /**
     * Substitutes each sentinel in {@code translatedMasked} back with the exact original bytes of the
     * corresponding Technical_Token (Req 7.1). Sentinels are replaced literally, so token content is
     * never re-interpreted.
     *
     * @param translatedMasked the provider's output over the masked text (sentinels expected intact)
     * @param original         the {@link MaskedText} produced by {@link #mask(String)}
     * @return the translated text with every surviving sentinel replaced by its original token
     */
    public String restore(String translatedMasked, MaskedText original) {
        if (translatedMasked == null) {
            return null;
        }
        if (original == null || original.tokens().isEmpty()) {
            return translatedMasked;
        }
        List<String> tokens = original.tokens();
        String result = translatedMasked;
        for (int i = 0; i < tokens.size(); i++) {
            // Literal (non-regex, non-replacement-escaped) substitution of sentinel -> exact bytes.
            result = result.replace(sentinel(i), tokens.get(i));
        }
        return result;
    }

    /**
     * Verifies that every Technical_Token from the Source_Text appears byte-for-byte in the restored
     * result (Req 7.4). The {@code TranslationService} uses this to decide whether to keep the
     * translation or fall back to the original Source_Text.
     *
     * @param restored the text produced by {@link #restore(String, MaskedText)}
     * @param original the {@link MaskedText} produced by {@link #mask(String)}
     * @return {@code true} only when every original token is present verbatim in {@code restored}
     */
    public boolean allTokensPreserved(String restored, MaskedText original) {
        if (original == null || original.tokens().isEmpty()) {
            return true;
        }
        if (restored == null) {
            return false;
        }
        for (String token : original.tokens()) {
            if (!restored.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String sentinel(int index) {
        return SENTINEL_OPEN + SENTINEL_MARK + index + SENTINEL_CLOSE;
    }

    /**
     * Builds the ordered alternation that detects Technical_Tokens. Alternatives are ordered from
     * most specific / longest to shortest because {@link Matcher#find()} selects the first matching
     * alternative at each position rather than the longest.
     */
    private static Pattern buildTokenPattern(java.util.Set<String> knownExecutables) {
        // Command text and arguments: a known executable, an optional single sub-command word, then
        // a run of technical-looking arguments (flags, path/host-ish tokens, ALL_CAPS codes, numbers).
        String execAlternation = knownExecutables.stream()
                .sorted((a, b) -> b.length() - a.length()) // longer first so "apt-get" beats "apt"
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        String arg = "(?:--?[\\w][\\w-]*(?:=\\S+)?|\\S*[/.:=]\\S*|[A-Z][A-Z0-9_]+|\\d[\\w.\\-]*)";
        String command = "\\b(?:" + execAlternation + ")\\b"
                + "(?:[ \\t]+[a-z][a-z0-9-]*)?"       // optional sub-command (git commit, kubectl apply)
                + "(?:[ \\t]+" + arg + ")*";           // trailing technical arguments

        String url = "\\b[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+";
        String windowsPath = "\\b[A-Za-z]:\\\\[^\\s]*";
        // Timestamp: ISO-8601 date with optional time, fractional seconds, and zone offset.
        String timestamp = "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?"
                + "(?:Z|[+-]\\d{2}:?\\d{2})?)?";
        String ipv4 = "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b";
        // Unix / relative file path: any run of path characters containing at least one slash.
        String unixPath = "[\\w.\\-]*/[\\w./\\-]*";
        // Host name: a dotted domain ending in an alphabetic TLD.
        String hostname = "\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\\b";
        // Dotted identifier / code fragment / method reference (IntentSessionManager.open).
        String dottedId = "\\b[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+(?:\\(\\))?";
        // Reason code: ALL_CAPS with at least one underscore (DUAL_CONTROL_REQUIRED).
        String reasonCode = "\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b";
        // Resource identifier / code fragment with internal hyphen or underscore (session-42, dual_control).
        String hyphenId = "\\b\\w+(?:[-_]\\w+)+\\b";
        // Numeric score: decimal first, then bare integer.
        String decimal = "\\b\\d+\\.\\d+\\b";
        String integer = "\\b\\d+\\b";

        String combined = String.join("|",
                command,
                url,
                windowsPath,
                timestamp,
                ipv4,
                unixPath,
                hostname,
                dottedId,
                reasonCode,
                hyphenId,
                decimal,
                integer);
        return Pattern.compile(combined);
    }
}
