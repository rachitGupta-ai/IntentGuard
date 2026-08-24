package com.intentguard.exfil;

import java.util.regex.Pattern;

/**
 * Deterministic glob-to-regex matching helper, kept package-private and self-contained so the
 * exfiltration guardrails do not depend on any other guardrail package.
 *
 * <p>Supports {@code **} (any run, including path separators), {@code *} (any run except
 * {@code /}), {@code ?} (any single character except {@code /}), and {@code [...]} character
 * classes (with a leading {@code !} treated as negation). All other characters match literally.
 * Matching is exact match (the whole candidate must match the compiled, anchored pattern).
 */
final class Globs {

    private Globs() {
    }

    /** Returns {@code true} when {@code candidate} matches the glob {@code pattern} exactly. */
    static boolean matches(String pattern, String candidate) {
        if (pattern == null || candidate == null) {
            return false;
        }
        return toRegex(pattern).matcher(candidate).matches();
    }

    /** Compiles a glob into an anchored {@link Pattern}. */
    static Pattern toRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        sb.append("\\[");
                    } else {
                        sb.append('[');
                        int start = i + 1;
                        if (start < close && glob.charAt(start) == '!') {
                            sb.append('^');
                            start++;
                        }
                        sb.append(glob, start, close);
                        sb.append(']');
                        i = close;
                    }
                }
                default -> {
                    if ("\\.^$+{}()|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
            i++;
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
