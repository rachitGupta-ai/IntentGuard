package com.intentguard.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.NotBlank;

/**
 * Property-based tests for {@link CommandValidator}.
 * Each property validates correctness invariants of the validator's pure functions.
 */
class CommandValidatorProperties {

    private static final List<String> KNOWN_COMMANDS_LIST =
            new ArrayList<>(CommandValidator.knownCommands());

    // ─── Property 1 ───────────────────────────────────────────────────────────────

    // Feature: gemini-translation-e2e-test, Property 1: Structural command validation accepts known commands with valid flags

    /**
     * Property 1: For any string whose first token is a known Linux command (case-insensitive),
     * followed by 0-5 tokens that are either flags (starting with -) or arbitrary arguments,
     * isStructurallyValid() shall return true.
     *
     * Validates: Requirements 1.4, 4.2, 4.3
     */
    @Property(tries = 200)
    void knownCommandWithValidFlagsIsStructurallyValid(
            @ForAll("knownCommandWithFlags") String commandString) {
        assertThat(CommandValidator.isStructurallyValid(commandString))
                .as("Expected isStructurallyValid() to return true for: \"%s\"", commandString)
                .isTrue();
    }

    @Provide
    Arbitrary<String> knownCommandWithFlags() {
        Arbitrary<String> commandName = Arbitraries.of(KNOWN_COMMANDS_LIST);

        Arbitrary<List<String>> additionalTokens = generateToken().list().ofMinSize(0).ofMaxSize(5);

        return Combinators.combine(commandName, additionalTokens).as((cmd, tokens) -> {
            if (tokens.isEmpty()) {
                return cmd;
            }
            return cmd + " " + tokens.stream().collect(Collectors.joining(" "));
        });
    }

    // ─── Property 2 ───────────────────────────────────────────────────────────────

    // Feature: gemini-translation-e2e-test, Property 2: Whitespace-only differences produce similarity 1.0

    /**
     * Property 2: For any valid Linux command string, if we introduce arbitrary whitespace
     * variations (leading/trailing spaces, multiple internal spaces collapsed to one),
     * CommandValidator.similarity() between the original and the whitespace-varied version
     * shall return 1.0.
     *
     * Validates: Requirements 1.6, 4.4
     */
    @Property(tries = 200)
    void whitespaceOnlyDifferencesProduceSimilarityOne(
            @ForAll("commandWithWhitespaceVariation") String[] pair) {
        String original = pair[0];
        String varied = pair[1];

        double score = CommandValidator.similarity(original, varied);

        assertThat(score)
                .as("Whitespace-only variation should produce similarity 1.0.\nOriginal: \"%s\"\nVaried:   \"%s\"",
                        original, varied)
                .isEqualTo(1.0);
    }

    @Provide
    Arbitrary<String[]> commandWithWhitespaceVariation() {
        Arbitrary<String> commandName = Arbitraries.of(KNOWN_COMMANDS_LIST);
        Arbitrary<List<String>> args = generateToken().list().ofMinSize(0).ofMaxSize(4);
        Arbitrary<String> leadingSpaces = Arbitraries.of("", " ", "  ", "   ");
        Arbitrary<String> trailingSpaces = Arbitraries.of("", " ", "  ", "   ");
        Arbitrary<String> internalSeparator = Arbitraries.of("  ", "   ", "    ", "\t", " \t ");

        return Combinators.combine(commandName, args, leadingSpaces, trailingSpaces, internalSeparator)
                .as((cmd, argList, lead, trail, sep) -> {
                    // Build the original command (clean, single-spaced)
                    String original;
                    if (argList.isEmpty()) {
                        original = cmd;
                    } else {
                        original = cmd + " " + argList.stream().collect(Collectors.joining(" "));
                    }

                    // Build the whitespace-varied version
                    String varied;
                    if (argList.isEmpty()) {
                        varied = lead + cmd + trail;
                    } else {
                        varied = lead + cmd + sep
                                + argList.stream().collect(Collectors.joining(sep))
                                + trail;
                    }

                    return new String[]{original, varied};
                });
    }

    // ─── Property 3 ───────────────────────────────────────────────────────────────

    // Feature: gemini-translation-e2e-test, Property 3: Similarity score is bounded in [0.0, 1.0]

    /**
     * Property 3: For any two non-empty strings passed to CommandValidator.similarity(),
     * the returned score shall be >= 0.0 and <= 1.0.
     *
     * Validates: Requirements 4.5
     */
    @Property(tries = 200)
    void similarityScoreIsBoundedBetweenZeroAndOne(
            @ForAll @NotBlank String a,
            @ForAll @NotBlank String b) {
        double score = CommandValidator.similarity(a, b);

        assertThat(score)
                .as("Similarity score for (\"%s\", \"%s\") must be in [0.0, 1.0]", a, b)
                .isBetween(0.0, 1.0);
    }

    // ─── Property 4 ───────────────────────────────────────────────────────────────

    // Feature: gemini-translation-e2e-test, Property 4: Different primary command name forces score to 0.0

    /**
     * Property 4: For any two command strings where the first whitespace-delimited token
     * (lowercased) of the translated output differs from the first token of the expected
     * command, CommandValidator.similarity() shall return exactly 0.0.
     *
     * Validates: Requirements 1.6, 4.6
     */
    @Property(tries = 200)
    void differentPrimaryCommandForcesScoreToZero(
            @ForAll("twoDistinctCommands") String[] pair) {
        String translated = pair[0];
        String expected = pair[1];

        double score = CommandValidator.similarity(translated, expected);

        assertThat(score)
                .as("Different primary commands should produce similarity 0.0.\nTranslated: \"%s\"\nExpected:   \"%s\"",
                        translated, expected)
                .isEqualTo(0.0);
    }

    @Provide
    Arbitrary<String[]> twoDistinctCommands() {
        // Ensure we pick two different commands from the known set
        return Arbitraries.of(KNOWN_COMMANDS_LIST).flatMap(cmd1 -> {
            List<String> remaining = KNOWN_COMMANDS_LIST.stream()
                    .filter(c -> !c.equals(cmd1))
                    .collect(Collectors.toList());

            return Arbitraries.of(remaining).flatMap(cmd2 -> {
                Arbitrary<List<String>> suffix1 = generateToken().list().ofMinSize(0).ofMaxSize(3);
                Arbitrary<List<String>> suffix2 = generateToken().list().ofMinSize(0).ofMaxSize(3);

                return Combinators.combine(suffix1, suffix2).as((s1, s2) -> {
                    String translated = s1.isEmpty() ? cmd1 : cmd1 + " " + String.join(" ", s1);
                    String expected = s2.isEmpty() ? cmd2 : cmd2 + " " + String.join(" ", s2);
                    return new String[]{translated, expected};
                });
            });
        });
    }

    // ─── Shared Generators ────────────────────────────────────────────────────────

    /**
     * Generates a single token that is either a flag (starting with - or --) or an argument.
     */
    private Arbitrary<String> generateToken() {
        Arbitrary<String> shortFlag = Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(3)
                .map(s -> "-" + s);

        Arbitrary<String> longFlag = Arbitraries.strings()
                .alpha().ofMinLength(2).ofMaxLength(10)
                .map(s -> "--" + s);

        Arbitrary<String> argument = Arbitraries.of(
                "/home", "/tmp", "/etc/hosts", ".", "..", "file.txt", "*.log",
                "/var/log/syslog", "test_dir", "archive.tar.gz", "pattern",
                "script.sh", "output", "123", "data"
        );

        return Arbitraries.oneOf(shortFlag, longFlag, argument);
    }
}
