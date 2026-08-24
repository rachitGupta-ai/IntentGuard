package com.intentguard.speech;

import com.intentguard.llm.LlmProperties;

/**
 * Test helper that exposes the package-private {@code resolveApiKey()} method of
 * {@link GeminiSpeechProvider} for cross-package property tests.
 */
public final class GeminiSpeechProviderKeyAccessor {

    private GeminiSpeechProviderKeyAccessor() {
    }

    /**
     * Creates a {@link GeminiSpeechProvider} and returns the resolved API key.
     */
    public static String resolveApiKey(LlmProperties llmProperties, SpeechProperties speechProperties) {
        GeminiSpeechProvider provider = new GeminiSpeechProvider(llmProperties, speechProperties, prompt -> "");
        return provider.resolveApiKey();
    }
}
