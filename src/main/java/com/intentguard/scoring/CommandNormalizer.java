package com.intentguard.scoring;

import java.util.Map;
import java.util.Set;

/**
 * Deterministic reduction of raw command text into the coarse forms the scoring components share:
 * the {@link #executable(String) executable} (used against the profile's vocabulary), a
 * {@link #normalizedToken(String) normalized token} of "executable + coarse argument shape" (used
 * against the profile's bigram {@code sequenceStats}), and a {@link #category(String) command
 * category} (used against {@code typedPastedRatioByCategory} and {@code contextAssociations}).
 *
 * <p>All methods are pure and case/whitespace-normalizing so identical commands always reduce to
 * identical forms, keeping every component deterministic.
 */
public final class CommandNormalizer {

    /** Category assigned when no known executable matches. */
    public static final String CATEGORY_OTHER = "other";

    // Executable -> coarse command category. Kept intentionally small and deterministic; unknown
    // executables fall back to CATEGORY_OTHER. Categories align with the design's profile examples
    // (e.g. "vcs", "network").
    private static final Map<String, String> CATEGORY_BY_EXECUTABLE = Map.ofEntries(
            Map.entry("git", "vcs"),
            Map.entry("svn", "vcs"),
            Map.entry("hg", "vcs"),
            Map.entry("curl", "network"),
            Map.entry("wget", "network"),
            Map.entry("ssh", "network"),
            Map.entry("scp", "network"),
            Map.entry("nc", "network"),
            Map.entry("telnet", "network"),
            Map.entry("ftp", "network"),
            Map.entry("kubectl", "orchestration"),
            Map.entry("helm", "orchestration"),
            Map.entry("docker", "orchestration"),
            Map.entry("npm", "package"),
            Map.entry("pip", "package"),
            Map.entry("pip3", "package"),
            Map.entry("apt", "package"),
            Map.entry("apt-get", "package"),
            Map.entry("yum", "package"),
            Map.entry("brew", "package"),
            Map.entry("mvn", "build"),
            Map.entry("gradle", "build"),
            Map.entry("make", "build"),
            Map.entry("ls", "filesystem"),
            Map.entry("cd", "filesystem"),
            Map.entry("cp", "filesystem"),
            Map.entry("mv", "filesystem"),
            Map.entry("rm", "filesystem"),
            Map.entry("mkdir", "filesystem"),
            Map.entry("touch", "filesystem"),
            Map.entry("cat", "filesystem"),
            Map.entry("sudo", "privilege"),
            Map.entry("su", "privilege"),
            Map.entry("chmod", "privilege"),
            Map.entry("chown", "privilege"));

    // Executables that take a leading sub-command word worth folding into the normalized token
    // (e.g. "git commit", "kubectl apply"). For these, a non-flag first argument sharpens the token.
    private static final Map<String, Boolean> HAS_SUBCOMMAND = Map.ofEntries(
            Map.entry("git", Boolean.TRUE),
            Map.entry("svn", Boolean.TRUE),
            Map.entry("hg", Boolean.TRUE),
            Map.entry("kubectl", Boolean.TRUE),
            Map.entry("helm", Boolean.TRUE),
            Map.entry("docker", Boolean.TRUE),
            Map.entry("npm", Boolean.TRUE),
            Map.entry("pip", Boolean.TRUE),
            Map.entry("pip3", Boolean.TRUE),
            Map.entry("apt", Boolean.TRUE),
            Map.entry("apt-get", Boolean.TRUE),
            Map.entry("yum", Boolean.TRUE),
            Map.entry("brew", Boolean.TRUE),
            Map.entry("mvn", Boolean.TRUE),
            Map.entry("gradle", Boolean.TRUE));

    private CommandNormalizer() {
    }

    /**
     * The executable of a command: the basename of the first whitespace-delimited token, lowercased.
     * Any leading directory path is stripped (so {@code /usr/bin/git} and {@code git} agree). Returns
     * the empty string for blank input.
     */
    public static String executable(String commandText) {
        String[] tokens = tokenize(commandText);
        if (tokens.length == 0) {
            return "";
        }
        return basename(tokens[0]);
    }

    /**
     * The normalized token "executable + coarse argument shape". For executables that take a
     * sub-command, a non-flag first argument is appended (e.g. {@code git commit}); otherwise, or
     * when the first argument is a flag, the token is just the executable (e.g. {@code ls}). This is
     * the token used as each side of a {@code "prev>curr"} entry in the profile's bigram stats.
     */
    public static String normalizedToken(String commandText) {
        String[] tokens = tokenize(commandText);
        if (tokens.length == 0) {
            return "";
        }
        String exec = basename(tokens[0]);
        if (Boolean.TRUE.equals(HAS_SUBCOMMAND.get(exec)) && tokens.length > 1) {
            String arg = tokens[1];
            if (!arg.isEmpty() && arg.charAt(0) != '-') {
                return exec + " " + arg.toLowerCase();
            }
        }
        return exec;
    }

    /**
     * The shared normalization vocabulary of known executables (for example {@code git},
     * {@code curl}, {@code kubectl}). This is the same vocabulary used to derive the
     * {@link #category(String) command category} and {@link #normalizedToken(String) normalized
     * token}; components that must detect command text (such as the translation layer's
     * Technical_Token protector) reuse it so command recognition stays consistent with scoring.
     *
     * @return an unmodifiable set of the recognized executable basenames (lower case)
     */
    public static Set<String> knownExecutables() {
        return CATEGORY_BY_EXECUTABLE.keySet();
    }

    /** The coarse command category for {@code commandText} (e.g. {@code vcs}), or {@code other}. */
    public static String category(String commandText) {
        String exec = executable(commandText);
        return CATEGORY_BY_EXECUTABLE.getOrDefault(exec, CATEGORY_OTHER);
    }

    private static String[] tokenize(String commandText) {
        if (commandText == null) {
            return new String[0];
        }
        String trimmed = commandText.strip();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    private static String basename(String token) {
        String t = token;
        int slash = t.lastIndexOf('/');
        if (slash >= 0 && slash < t.length() - 1) {
            t = t.substring(slash + 1);
        }
        return t.toLowerCase();
    }
}
