package com.intentguard.assist;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link CommandAlternative} validation.
 *
 * <p><b>Validates: Requirements 2.1, 2.2</b>
 *
 * <p>Property 2 (validation subset): Generation produces 2-3 alternatives with explanations.
 * This test class verifies the record's compact constructor validation logic:
 * any CommandAlternative with null/blank command or explanation, or negative index,
 * throws the appropriate exception; valid inputs always construct successfully.
 */
class CommandAlternativePropertyTest {

    /**
     * Property: any non-null, non-blank command + non-null, non-blank explanation
     * + non-negative index constructs successfully and preserves all field values.
     */
    @Property(tries = 200)
    void validInputsConstructSuccessfully(
            @ForAll("nonBlankStrings") String command,
            @ForAll("nonBlankStrings") String explanation,
            @ForAll @IntRange(min = 0, max = 100) int index) {

        CommandAlternative alt = new CommandAlternative(command, explanation, index);

        assertThat(alt.command()).isEqualTo(command);
        assertThat(alt.explanation()).isEqualTo(explanation);
        assertThat(alt.index()).isEqualTo(index);
    }

    /**
     * Property: null command throws NullPointerException.
     */
    @Property(tries = 50)
    void nullCommandThrowsNullPointerException(
            @ForAll("nonBlankStrings") String explanation,
            @ForAll @IntRange(min = 0, max = 100) int index) {

        assertThatThrownBy(() -> new CommandAlternative(null, explanation, index))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("command");
    }

    /**
     * Property: blank command throws IllegalArgumentException.
     */
    @Property(tries = 50)
    void blankCommandThrowsIllegalArgumentException(
            @ForAll("blankStrings") String blankCommand,
            @ForAll("nonBlankStrings") String explanation,
            @ForAll @IntRange(min = 0, max = 100) int index) {

        assertThatThrownBy(() -> new CommandAlternative(blankCommand, explanation, index))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    /**
     * Property: null explanation throws NullPointerException.
     */
    @Property(tries = 50)
    void nullExplanationThrowsNullPointerException(
            @ForAll("nonBlankStrings") String command,
            @ForAll @IntRange(min = 0, max = 100) int index) {

        assertThatThrownBy(() -> new CommandAlternative(command, null, index))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("explanation");
    }

    /**
     * Property: blank explanation throws IllegalArgumentException.
     */
    @Property(tries = 50)
    void blankExplanationThrowsIllegalArgumentException(
            @ForAll("nonBlankStrings") String command,
            @ForAll("blankStrings") String blankExplanation,
            @ForAll @IntRange(min = 0, max = 100) int index) {

        assertThatThrownBy(() -> new CommandAlternative(command, blankExplanation, index))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explanation");
    }

    /**
     * Property: negative index throws IllegalArgumentException.
     */
    @Property(tries = 50)
    void negativeIndexThrowsIllegalArgumentException(
            @ForAll("nonBlankStrings") String command,
            @ForAll("nonBlankStrings") String explanation,
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int negativeIndex) {

        assertThatThrownBy(() -> new CommandAlternative(command, explanation, negativeIndex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index");
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(200)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of("", " ", "\t", "\n", "  ", "\t\n", "   \t  ");
    }
}
