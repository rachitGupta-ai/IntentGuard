package com.intentguard.llm;

/**
 * Minimal seam over the Gemini SDK's text generation. The production implementation wraps the
 * Gemini {@code Client}; tests inject a fake so the adapter's timeout, parsing, and fallback
 * behavior can be exercised without any network or API key.
 */
@FunctionalInterface
interface GeminiTextGenerator {

    /**
     * Generates model text for the given prompt.
     *
     * @param prompt the fully-built prompt
     * @return the model's raw text response
     * @throws Exception on any SDK/transport failure (treated by the adapter as an error)
     */
    String generate(String prompt) throws Exception;
}
