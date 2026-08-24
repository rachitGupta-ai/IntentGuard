package com.intentguard.e2e;

import java.util.Set;

/**
 * Validates translated command output against expected Linux commands.
 * Pure functions suitable for property-based testing.
 */
public final class CommandValidator {

    /** At least 23 known Linux command names (Req 4.1). */
    private static final Set<String> KNOWN_COMMANDS = Set.of(
        "ls", "cd", "mkdir", "rmdir", "cp", "mv", "rm", "cat", "grep", "find",
        "chmod", "chown", "ps", "kill", "echo", "pwd", "whoami", "uname",
        "df", "du", "tar", "wget", "curl", "head", "tail", "touch", "man"
    );

    private CommandValidator() {
        // Utility class — no instantiation
    }

    /**
     * Normalizes a command string: trims and collapses internal whitespace to single spaces.
     */
    static String normalize(String command) {
        if (command == null) {
            return "";
        }
        return command.trim().replaceAll("\\s+", " ");
    }

    /**
     * Extracts the primary command name (first whitespace-delimited token, lowercased).
     */
    static String primaryCommand(String command) {
        String normalized = normalize(command);
        if (normalized.isEmpty()) {
            return "";
        }
        int spaceIndex = normalized.indexOf(' ');
        String firstToken = spaceIndex == -1 ? normalized : normalized.substring(0, spaceIndex);
        return firstToken.toLowerCase();
    }

    /**
     * Checks whether a token is a valid flag (starts with '-' or '--').
     */
    static boolean isFlag(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return token.startsWith("-");
    }

    /**
     * Computes normalized Levenshtein similarity between two strings.
     * Returns a value in [0.0, 1.0] where 1.0 means identical strings.
     */
    static double levenshteinSimilarity(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.equals(b)) {
            return 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Validates that the translated output is a structurally valid command.
     * Checks: first token is a known command, flags start with hyphen.
     *
     * @param translated the translated English output
     * @return true if structurally valid (known command + valid flag format)
     */
    public static boolean isStructurallyValid(String translated) {
        if (translated == null || translated.isBlank()) {
            return false;
        }
        String normalized = normalize(translated);
        String[] tokens = normalized.split(" ");

        // First token must be a known command (case-insensitive)
        String command = tokens[0].toLowerCase();
        if (!KNOWN_COMMANDS.contains(command)) {
            return false;
        }

        // Remaining tokens are either flags (starting with '-') or arguments.
        // Per Req 4.3: flags starting with '-' or '--' are accepted as valid command components.
        // Non-hyphen tokens are valid arguments. No tokens need to be rejected here.

        return true;
    }

    /**
     * Computes similarity between translated output and expected command.
     * Returns 0.0 immediately if primary command names differ (Req 4.6).
     * Normalizes whitespace before comparison (Req 4.4).
     * Uses Levenshtein distance normalized by max length (Req 4.5).
     *
     * @param translated the actual translated output
     * @param expected   the expected English command
     * @return similarity score in [0.0, 1.0]
     */
    public static double similarity(String translated, String expected) {
        if (translated == null || expected == null) {
            return 0.0;
        }
        if (translated.isBlank() || expected.isBlank()) {
            return 0.0;
        }

        // If primary commands differ, return 0.0 immediately
        String translatedCmd = primaryCommand(translated);
        String expectedCmd = primaryCommand(expected);
        if (!translatedCmd.equals(expectedCmd)) {
            return 0.0;
        }

        // Normalize whitespace before computing similarity
        String normalizedTranslated = normalize(translated);
        String normalizedExpected = normalize(expected);

        return levenshteinSimilarity(normalizedTranslated, normalizedExpected);
    }

    /**
     * Returns the set of known Linux command names.
     */
    public static Set<String> knownCommands() {
        return KNOWN_COMMANDS;
    }

    /**
     * Computes the Levenshtein edit distance between two strings using dynamic programming.
     */
    private static int levenshteinDistance(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();

        // Use a single-row DP approach for space efficiency
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                );
            }
            // Swap rows
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[lenB];
    }
}
