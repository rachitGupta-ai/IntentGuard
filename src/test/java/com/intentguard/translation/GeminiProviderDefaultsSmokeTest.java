package com.intentguard.translation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.GeminiSpeechProvider;
import com.intentguard.speech.SpeechProperties;

/**
 * Smoke tests verifying the application context loads correctly with Gemini provider defaults
 * and that the {@code application.yml} property expressions are configured as specified.
 *
 * <p>Validates:
 * <ul>
 *   <li><strong>Req 7.1:</strong> {@code intentguard.translation.provider} defaults to {@code "gemini"}</li>
 *   <li><strong>Req 7.2:</strong> {@code intentguard.speech.provider} defaults to {@code "gemini"}</li>
 *   <li><strong>Req 7.3:</strong> {@code intentguard.translation.api-key} uses the nested fallback
 *       expression {@code ${TRANSLATION_API_KEY:${GEMINI_API_KEY:}}}</li>
 *   <li><strong>Req 7.4:</strong> {@code intentguard.speech.api-key} uses the nested fallback
 *       expression {@code ${SPEECH_API_KEY:${GEMINI_API_KEY:}}}</li>
 *   <li><strong>Req 10.3:</strong> Existing bhashini provider still selects correctly</li>
 *   <li><strong>Req 10.4:</strong> Existing cloud provider still selects correctly</li>
 * </ul>
 */
class GeminiProviderDefaultsSmokeTest {

    // ---- Req 7.1, 7.2: Gemini provider defaults -------------------------------------------------

    @Test
    void geminiTranslationProviderLoadsWithApiKeySet() {
        TranslationProperties translation = new TranslationProperties();
        translation.setProvider("gemini");
        translation.setApiKey("test-gemini-key");
        SpeechProperties speech = new SpeechProperties();
        speech.setProvider("gemini");
        speech.setApiKey("test-gemini-key");

        TranslationRuntimeConfig config = new TranslationRuntimeConfig(translation, speech);
        config.initialize();

        assertThat(config.getActive().translationProviderId()).isEqualTo("gemini");
        assertThat(config.getActive().speechProviderId()).isEqualTo("gemini");
        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isTrue();
    }

    @Test
    void geminiTranslationProviderLoadsInDegradedModeWithBlankKey() {
        TranslationProperties translation = new TranslationProperties();
        translation.setProvider("gemini");
        translation.setApiKey("");
        SpeechProperties speech = new SpeechProperties();
        speech.setProvider("gemini");
        speech.setApiKey("");

        TranslationRuntimeConfig config = new TranslationRuntimeConfig(translation, speech);
        config.initialize();

        // Provider identity is still gemini; capabilities are disabled due to blank key.
        assertThat(config.getActive().translationProviderId()).isEqualTo("gemini");
        assertThat(config.getActive().speechProviderId()).isEqualTo("gemini");
        assertThat(config.isTextTranslationEnabled()).isFalse();
        assertThat(config.isSpeechEnabled()).isFalse();
    }

    @Test
    void geminiTranslationProviderIdReturnsGemini() {
        LlmProperties llm = new LlmProperties();
        llm.setApiKey("");
        TranslationProperties translation = new TranslationProperties();
        translation.setApiKey("");

        GeminiTranslationProvider provider = new GeminiTranslationProvider(llm, translation);

        assertThat(provider.id()).isEqualTo("gemini");
    }

    @Test
    void geminiSpeechProviderIdReturnsGemini() {
        LlmProperties llm = new LlmProperties();
        llm.setApiKey("");
        SpeechProperties speech = new SpeechProperties();
        speech.setApiKey("");

        GeminiSpeechProvider provider = new GeminiSpeechProvider(llm, speech);

        assertThat(provider.id()).isEqualTo("gemini");
    }

    // ---- Req 7.3, 7.4: application.yml nested fallback expressions ------------------------------

    @Test
    void applicationYmlContainsGeminiTranslationProviderDefault() throws IOException {
        String yaml = loadApplicationYml();

        // Req 7.1: provider defaults to gemini
        assertThat(yaml).contains("provider: gemini");
    }

    @Test
    void applicationYmlContainsNestedTranslationApiKeyFallback() throws IOException {
        String yaml = loadApplicationYml();

        // Req 7.3: translation api-key uses nested fallback expression
        assertThat(yaml).contains("${TRANSLATION_API_KEY:${GEMINI_API_KEY:}}");
    }

    @Test
    void applicationYmlContainsNestedSpeechApiKeyFallback() throws IOException {
        String yaml = loadApplicationYml();

        // Req 7.4: speech api-key uses nested fallback expression
        assertThat(yaml).contains("${SPEECH_API_KEY:${GEMINI_API_KEY:}}");
    }

    // ---- Req 10.3, 10.4: existing providers still resolve correctly when configured -------------

    @Test
    void bhashiniProviderResolvedWhenConfigured() {
        TranslationProperties translation = new TranslationProperties();
        translation.setProvider("bhashini");
        translation.setApiKey("bhashini-key");
        SpeechProperties speech = new SpeechProperties();
        speech.setProvider("bhashini");
        speech.setApiKey("bhashini-key");

        TranslationRuntimeConfig config = new TranslationRuntimeConfig(translation, speech);
        config.initialize();

        assertThat(config.getActive().translationProviderId()).isEqualTo("bhashini");
        assertThat(config.getActive().speechProviderId()).isEqualTo("bhashini");
        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isTrue();
    }

    @Test
    void cloudProviderResolvedWhenConfigured() {
        TranslationProperties translation = new TranslationProperties();
        translation.setProvider("cloud");
        translation.setApiKey("cloud-key");
        SpeechProperties speech = new SpeechProperties();
        speech.setProvider("cloud");
        speech.setApiKey("cloud-key");

        TranslationRuntimeConfig config = new TranslationRuntimeConfig(translation, speech);
        config.initialize();

        assertThat(config.getActive().translationProviderId()).isEqualTo("cloud");
        assertThat(config.getActive().speechProviderId()).isEqualTo("cloud");
        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isTrue();
    }

    @Test
    void existingProviderSelectionUnchangedByGeminiRegistration() {
        // Gemini provider being registered should not alter how bhashini/cloud are selected
        // when explicitly configured.
        LlmProperties llm = new LlmProperties();
        llm.setApiKey("gemini-key");
        TranslationProperties translationProps = new TranslationProperties();
        translationProps.setProvider("bhashini");
        translationProps.setApiKey("bhashini-key");

        GeminiTranslationProvider geminiProvider = new GeminiTranslationProvider(llm, translationProps);
        BhashiniTranslationProvider bhashiniProvider = new BhashiniTranslationProvider(translationProps);
        CloudTranslationProvider cloudProvider = new CloudTranslationProvider(translationProps);

        // All three providers have distinct IDs
        assertThat(geminiProvider.id()).isEqualTo("gemini");
        assertThat(bhashiniProvider.id()).isEqualTo("bhashini");
        assertThat(cloudProvider.id()).isEqualTo("cloud");

        // Provider lookup by ID finds the correct provider for each
        List<TranslationProvider> providers = List.of(geminiProvider, bhashiniProvider, cloudProvider);
        assertThat(providers.stream().filter(p -> p.id().equals("bhashini")).findFirst())
                .isPresent()
                .get()
                .isSameAs(bhashiniProvider);
        assertThat(providers.stream().filter(p -> p.id().equals("cloud")).findFirst())
                .isPresent()
                .get()
                .isSameAs(cloudProvider);
        assertThat(providers.stream().filter(p -> p.id().equals("gemini")).findFirst())
                .isPresent()
                .get()
                .isSameAs(geminiProvider);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String loadApplicationYml() throws IOException {
        try (InputStream is = GeminiProviderDefaultsSmokeTest.class
                .getResourceAsStream("/application.yml")) {
            if (is == null) {
                // Fallback: load from classpath root (prod resources are on the test classpath)
                try (InputStream fallback = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream("application.yml")) {
                    assertThat(fallback).as("application.yml must be on the classpath").isNotNull();
                    return new String(fallback.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
