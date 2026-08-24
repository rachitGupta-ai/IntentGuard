package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.GeminiSpeechProvider;
import com.intentguard.speech.SpeechProperties;
import com.intentguard.speech.SpeechProvider;

/**
 * Unit tests verifying that the Gemini providers return the correct identity string and are
 * discoverable as Spring beans via their respective interfaces.
 *
 * <p>These are direct-construction tests (no Spring context): they confirm the {@code id()} contract
 * and that the providers implement the correct interfaces, which is sufficient for Spring's
 * component scanning to register them as beans matched by
 * {@link TranslationRuntimeConfig#getActive()}.
 *
 * <p>Validates: Requirements 1.1, 1.2, 3.1, 3.2.
 */
class GeminiProviderRegistrationTest {

    // ---- Requirement 1.1: GeminiTranslationProvider.id() returns "gemini" -----------------------

    @Test
    void geminiTranslationProviderReturnsGeminiId() {
        LlmProperties llmProperties = new LlmProperties();
        TranslationProperties translationProperties = new TranslationProperties();

        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                llmProperties, translationProperties);

        assertThat(provider.id()).isEqualTo("gemini");
    }

    // ---- Requirement 3.1: GeminiSpeechProvider.id() returns "gemini" ----------------------------

    @Test
    void geminiSpeechProviderReturnsGeminiId() {
        LlmProperties llmProperties = new LlmProperties();
        SpeechProperties speechProperties = new SpeechProperties();

        GeminiSpeechProvider provider = new GeminiSpeechProvider(
                llmProperties, speechProperties);

        assertThat(provider.id()).isEqualTo("gemini");
    }

    // ---- Requirement 1.2: GeminiTranslationProvider is a TranslationProvider bean ---------------

    @Test
    void geminiTranslationProviderImplementsTranslationProviderInterface() {
        LlmProperties llmProperties = new LlmProperties();
        TranslationProperties translationProperties = new TranslationProperties();

        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                llmProperties, translationProperties);

        // The provider implements TranslationProvider, which is the interface Spring uses
        // for bean discovery in TranslationRuntimeConfig's provider-selection mechanism.
        assertThat(provider).isInstanceOf(TranslationProvider.class);
    }

    // ---- Requirement 3.2: GeminiSpeechProvider is a SpeechProvider bean -------------------------

    @Test
    void geminiSpeechProviderImplementsSpeechProviderInterface() {
        LlmProperties llmProperties = new LlmProperties();
        SpeechProperties speechProperties = new SpeechProperties();

        GeminiSpeechProvider provider = new GeminiSpeechProvider(
                llmProperties, speechProperties);

        // The provider implements SpeechProvider, which is the interface Spring uses
        // for bean discovery in the speech provider-selection mechanism.
        assertThat(provider).isInstanceOf(SpeechProvider.class);
    }

    // ---- Both providers are annotated with @Component (bean registration) -----------------------

    @Test
    void geminiTranslationProviderIsAnnotatedAsComponent() {
        // Verify that the class is annotated with @Component, which makes it discoverable
        // by Spring's component scanning.
        assertThat(GeminiTranslationProvider.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class)).isTrue();
    }

    @Test
    void geminiSpeechProviderIsAnnotatedAsComponent() {
        // Verify that the class is annotated with @Component, which makes it discoverable
        // by Spring's component scanning.
        assertThat(GeminiSpeechProvider.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class)).isTrue();
    }
}
