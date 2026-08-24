package com.intentguard.assist;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests and property-based tests for {@link GeminiCommandGenerator}.
 *
 * <p><b>Validates: Requirements 2.3</b>
 *
 * <p>Property 3 (generation subset): LLM failure prevents execution —
 * any exception from the text generator is wrapped into {@link AssistGenerationException}.
 */
class GeminiCommandGeneratorTest {

    // --- Unit Tests ---

    @Test
    void successfulGenerationReturnsAlternatives() {
        String validJson = """
                [
                  {"command": "ls -la", "explanation": "Lists all files including hidden ones with details"},
                  {"command": "find . -maxdepth 1 -type f", "explanation": "Finds all files in the current directory"},
                  {"command": "tree -L 1", "explanation": "Shows directory tree one level deep"}
                ]
                """;
        AssistTextGenerator stub = prompt -> validJson;
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        List<CommandAlternative> result = generator.generate("list files in current directory", List.of());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).command()).isEqualTo("ls -la");
        assertThat(result.get(0).explanation()).isEqualTo("Lists all files including hidden ones with details");
        assertThat(result.get(0).index()).isEqualTo(0);
        assertThat(result.get(1).command()).isEqualTo("find . -maxdepth 1 -type f");
        assertThat(result.get(1).index()).isEqualTo(1);
        assertThat(result.get(2).command()).isEqualTo("tree -L 1");
        assertThat(result.get(2).index()).isEqualTo(2);
    }

    @Test
    void handlesMarkdownCodeFencedResponse() {
        String fencedJson = """
                ```json
                [
                  {"command": "df -h", "explanation": "Shows disk usage in human-readable format"},
                  {"command": "du -sh *", "explanation": "Shows size of each item in current directory"}
                ]
                ```""";
        AssistTextGenerator stub = prompt -> fencedJson;
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        List<CommandAlternative> result = generator.generate("check disk space", List.of());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).command()).isEqualTo("df -h");
        assertThat(result.get(1).command()).isEqualTo("du -sh *");
    }

    @Test
    void malformedJsonThrowsAssistGenerationException() {
        String malformedJson = "this is not valid JSON at all {[}";
        AssistTextGenerator stub = prompt -> malformedJson;
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        assertThatThrownBy(() -> generator.generate("do something", List.of()))
                .isInstanceOf(AssistGenerationException.class)
                .hasMessageContaining("Failed to parse LLM response");
    }

    @Test
    void emptyResponseThrowsAssistGenerationException() {
        AssistTextGenerator stub = prompt -> "";
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        assertThatThrownBy(() -> generator.generate("do something", List.of()))
                .isInstanceOf(AssistGenerationException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    void sdkExceptionWrappedInAssistGenerationException() {
        AssistTextGenerator stub = prompt -> {
            throw new RuntimeException("Gemini SDK connection timeout");
        };
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        assertThatThrownBy(() -> generator.generate("restart the server", List.of()))
                .isInstanceOf(AssistGenerationException.class)
                .hasMessageContaining("Command generation failed");
    }

    // --- Property-Based Test ---

    /**
     * Property 3 (generation subset): For any exception type thrown by the text generator,
     * the command generator always wraps it in {@link AssistGenerationException}.
     * This guarantees that LLM failures never propagate unexpected exception types to callers,
     * ensuring a single failure path for error handling.
     *
     * <p><b>Validates: Requirements 2.3</b>
     */
    @Property(tries = 100)
    void anyExceptionFromGeneratorProducesAssistGenerationException(
            @ForAll("exceptions") Exception thrownException) {

        AssistTextGenerator stub = prompt -> {
            throw thrownException;
        };
        GeminiCommandGenerator generator = new GeminiCommandGenerator(stub);

        assertThatThrownBy(() -> generator.generate("any query", List.of()))
                .isInstanceOf(AssistGenerationException.class);
    }

    // --- Providers ---

    @Provide
    Arbitrary<Exception> exceptions() {
        return Arbitraries.of(
                new RuntimeException("connection reset"),
                new IOException("network unreachable"),
                new TimeoutException("request timed out"),
                new IllegalStateException("service unavailable"),
                new NullPointerException("unexpected null in SDK"),
                new OutOfMemoryError("heap space").initCause(null) instanceof Throwable
                        ? new RuntimeException("wrapped OOM")
                        : new RuntimeException("wrapped OOM"),
                new InterruptedException("thread interrupted"),
                new UnsupportedOperationException("model not available"),
                new SecurityException("API key revoked"),
                new ArithmeticException("token limit overflow")
        ).map(e -> e);
    }
}
