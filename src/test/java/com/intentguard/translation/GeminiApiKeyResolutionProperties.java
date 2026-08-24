package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.GeminiSpeechProviderKeyAccessor;
import com.intentguard.speech.SpeechProperties;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

// Feature: gemini-translation-provider, Property 4: API key resolution — dedicated takes priority over fallback

/**
 * Property 4: API key resolution — dedicated takes priority over fallback.
 *
 * <p>For any combination of dedicated key and LLM key values: when the dedicated key is non-blank,
 * the provider uses the dedicated key regardless of the LLM key; when the dedicated key is blank,
 * the provider resolves to the LLM key. This applies to both translation and speech providers.
 *
 * <p><strong>Validates: Requirements 6.1, 6.2, 6.3, 6.4</strong>
 */
class GeminiApiKeyResolutionProperties {

    // ---- Translation Provider ----

    @Property(tries = 200)
    void translationProvider_dedicatedNonBlank_usesDedicatedKey(
            @ForAll("nonBlankKey") String dedicatedKey,
            @ForAll("anyKey") String llmKey) {
        // Validates: Requirements 6.1, 6.3
        LlmProperties llmProps = llmProperties(llmKey);
        TranslationProperties translationProps = translationProperties(dedicatedKey);

        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                llmProps, translationProps, prompt -> "dummy");

        String resolved = provider.resolveApiKey();
        assertThat(resolved).isEqualTo(dedicatedKey);
    }

    @Property(tries = 200)
    void translationProvider_dedicatedBlank_fallsBackToLlmKey(
            @ForAll("blankKey") String dedicatedKey,
            @ForAll("anyKey") String llmKey) {
        // Validates: Requirements 6.1, 6.3
        LlmProperties llmProps = llmProperties(llmKey);
        TranslationProperties translationProps = translationProperties(dedicatedKey);

        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                llmProps, translationProps, prompt -> "dummy");

        String resolved = provider.resolveApiKey();
        assertThat(resolved).isEqualTo(llmKey);
    }

    // ---- Speech Provider ----

    @Property(tries = 200)
    void speechProvider_dedicatedNonBlank_usesDedicatedKey(
            @ForAll("nonBlankKey") String dedicatedKey,
            @ForAll("anyKey") String llmKey) {
        // Validates: Requirements 6.2, 6.4
        LlmProperties llmProps = llmProperties(llmKey);
        SpeechProperties speechProps = speechProperties(dedicatedKey);

        String resolved = GeminiSpeechProviderKeyAccessor.resolveApiKey(llmProps, speechProps);
        assertThat(resolved).isEqualTo(dedicatedKey);
    }

    @Property(tries = 200)
    void speechProvider_dedicatedBlank_fallsBackToLlmKey(
            @ForAll("blankKey") String dedicatedKey,
            @ForAll("anyKey") String llmKey) {
        // Validates: Requirements 6.2, 6.4
        LlmProperties llmProps = llmProperties(llmKey);
        SpeechProperties speechProps = speechProperties(dedicatedKey);

        String resolved = GeminiSpeechProviderKeyAccessor.resolveApiKey(llmProps, speechProps);
        assertThat(resolved).isEqualTo(llmKey);
    }

    // ---- Arbitraries ----

    @Provide
    Arbitrary<String> nonBlankKey() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .withChars('-', '_')
                .ofMinLength(1)
                .ofMaxLength(64)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<String> blankKey() {
        return Arbitraries.of("", "   ", "\t", "\n", " \t\n ");
    }

    @Provide
    Arbitrary<String> anyKey() {
        return Arbitraries.oneOf(
                nonBlankKey(),
                blankKey(),
                Arbitraries.just(null)
        );
    }

    // ---- Helpers ----

    private static LlmProperties llmProperties(String apiKey) {
        LlmProperties props = new LlmProperties();
        props.setApiKey(apiKey);
        props.setModel("gemini-2.5-flash");
        return props;
    }

    private static TranslationProperties translationProperties(String apiKey) {
        TranslationProperties props = new TranslationProperties();
        props.setApiKey(apiKey);
        return props;
    }

    private static SpeechProperties speechProperties(String apiKey) {
        SpeechProperties props = new SpeechProperties();
        props.setApiKey(apiKey);
        return props;
    }
}
