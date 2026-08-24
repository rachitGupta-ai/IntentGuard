package com.intentguard.assist;

/**
 * Public seam over the Gemini SDK for command generation. The production bean wraps the Gemini
 * {@code Client}; tests inject a stub so prompt-building, parsing, and failure handling can be
 * exercised without any network call.
 *
 * <p>This mirrors the package-private {@code GeminiTextGenerator} in the {@code llm} package but
 * is accessible from the {@code assist} package for command generation use cases.
 */
@FunctionalInterface
public interface AssistTextGenerator {

    /**
     * Generates model text for the given prompt.
     *
     * @param prompt the fully-built prompt
     * @return the model's raw text response
     * @throws Exception on any SDK/transport failure
     */
    String generate(String prompt) throws Exception;
}
