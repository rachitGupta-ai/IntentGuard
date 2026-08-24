package com.intentguard.speech;

/**
 * Minimal seam over the Gemini SDK's text generation for speech recognition calls. The production
 * implementation wraps the Gemini {@code Client}; tests inject a fake so the provider's timeout,
 * parsing, and fallback behavior can be exercised without any network or API key.
 *
 * <p>This is a package-private copy of the same pattern used in
 * {@code com.intentguard.llm.GeminiTextGenerator} and {@code com.intentguard.translation.GeminiTextGenerator},
 * kept in this package to avoid cross-package visibility issues with the package-private test seam.
 */
@FunctionalInterface
public interface GeminiTextGenerator {

    /**
     * Generates model text for the given prompt.
     *
     * @param prompt the fully-built prompt
     * @return the model's raw text response
     * @throws Exception on any SDK/transport failure (treated by the adapter as an error)
     */
    String generate(String prompt) throws Exception;
}
