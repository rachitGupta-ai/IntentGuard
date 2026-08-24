package com.intentguard.translation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.SpeechProperties;

/**
 * Unit tests verifying that the Gemini translation provider does not interfere with the existing
 * Bhashini and Cloud providers (Req 1.4, 3.4, 10.1, 10.2, 10.3, 10.4).
 *
 * <p>When the runtime configuration selects {@code "bhashini"} or {@code "cloud"} as the active
 * Translation_Provider identity, the {@link DefaultTranslationService} resolves and uses the
 * corresponding existing provider exactly as before — the presence of the Gemini provider in the
 * bean list has no effect on their behavior or selection.
 */
class GeminiNonInterferenceTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");

    // ──────────────────────────────────────────────────────────────────────────────
    // Requirement 10.3: When provider=bhashini, TranslationRuntimeConfig selects Bhashini
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void bhashiniProviderIsSelectedWhenConfiguredAlongsideGemini() {
        // Arrange: register all three providers in the service (simulating Spring bean list)
        PassthroughTranslationProvider bhashiniProvider = new PassthroughTranslationProvider("bhashini");
        PassthroughTranslationProvider cloudProvider = new PassthroughTranslationProvider("cloud");
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-gemini-key");
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setApiKey("test-key");
        GeminiTranslationProvider geminiProvider =
                new GeminiTranslationProvider(llmProperties, translationProperties, prompt -> "gemini-response");

        // Configure runtime to select "bhashini"
        translationProperties.setProvider("bhashini");
        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("bhashini");

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        DefaultTranslationService service = new DefaultTranslationService(
                List.of(bhashiniProvider, cloudProvider, geminiProvider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());

        // Act
        TranslationResult result = service.translate("hello world", SupportedLanguages.ENGLISH, HINDI);

        // Assert: Bhashini provider was used (not Gemini)
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(bhashiniProvider.invocationCount()).isEqualTo(1);
        assertThat(cloudProvider.invocationCount()).isZero();
    }

    @Test
    void bhashiniSnapshotContainsBhashiniProviderIdWhenConfigured() {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider("bhashini");
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("bhashini");

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        TranslationRuntimeConfig.Snapshot snapshot = runtimeConfig.getActive();
        assertThat(snapshot.translationProviderId()).isEqualTo("bhashini");
        assertThat(snapshot.speechProviderId()).isEqualTo("bhashini");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Requirement 10.3: When provider=cloud, TranslationRuntimeConfig selects Cloud
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void cloudProviderIsSelectedWhenConfiguredAlongsideGemini() {
        // Arrange: register all three providers
        PassthroughTranslationProvider bhashiniProvider = new PassthroughTranslationProvider("bhashini");
        PassthroughTranslationProvider cloudProvider = new PassthroughTranslationProvider("cloud");
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-gemini-key");
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setApiKey("test-key");
        GeminiTranslationProvider geminiProvider =
                new GeminiTranslationProvider(llmProperties, translationProperties, prompt -> "gemini-response");

        // Configure runtime to select "cloud"
        translationProperties.setProvider("cloud");
        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("cloud");

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        DefaultTranslationService service = new DefaultTranslationService(
                List.of(bhashiniProvider, cloudProvider, geminiProvider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());

        // Act
        TranslationResult result = service.translate("hello world", SupportedLanguages.ENGLISH, HINDI);

        // Assert: Cloud provider was used (not Gemini)
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(cloudProvider.invocationCount()).isEqualTo(1);
        assertThat(bhashiniProvider.invocationCount()).isZero();
    }

    @Test
    void cloudSnapshotContainsCloudProviderIdWhenConfigured() {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider("cloud");
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("cloud");

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        TranslationRuntimeConfig.Snapshot snapshot = runtimeConfig.getActive();
        assertThat(snapshot.translationProviderId()).isEqualTo("cloud");
        assertThat(snapshot.speechProviderId()).isEqualTo("cloud");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Requirement 10.1, 10.2: No modification to existing provider classes
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void existingProviderIdsRemainUnchanged() {
        // Verify that the existing providers continue to return their established identities.
        // This guards against accidental modifications that could break provider selection.
        TranslationProperties properties = new TranslationProperties();
        properties.setApiKey("test-key");

        BhashiniTranslationProvider bhashini = new BhashiniTranslationProvider(properties);
        CloudTranslationProvider cloud = new CloudTranslationProvider(properties);

        assertThat(bhashini.id()).isEqualTo("bhashini");
        assertThat(cloud.id()).isEqualTo("cloud");
    }

    @Test
    void geminiProviderIdDoesNotConflictWithExistingProviders() {
        TranslationProperties properties = new TranslationProperties();
        properties.setApiKey("test-key");
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-key");

        BhashiniTranslationProvider bhashini = new BhashiniTranslationProvider(properties);
        CloudTranslationProvider cloud = new CloudTranslationProvider(properties);
        GeminiTranslationProvider gemini =
                new GeminiTranslationProvider(llmProperties, properties, prompt -> "response");

        // All three providers have distinct identities so they don't collide in the map
        assertThat(bhashini.id()).isNotEqualTo(gemini.id());
        assertThat(cloud.id()).isNotEqualTo(gemini.id());
        assertThat(bhashini.id()).isNotEqualTo(cloud.id());
    }

    @Test
    void allThreeProvidersCoexistInServiceWithoutConflict() {
        // When all three providers are registered and the config points to "gemini",
        // only the Gemini provider is used — proving coexistence without interference.
        PassthroughTranslationProvider bhashiniProvider = new PassthroughTranslationProvider("bhashini");
        PassthroughTranslationProvider cloudProvider = new PassthroughTranslationProvider("cloud");
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-gemini-key");
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setApiKey("test-key");
        translationProperties.setProvider("gemini");
        GeminiTranslationProvider geminiProvider =
                new GeminiTranslationProvider(llmProperties, translationProperties, prompt -> "translated");

        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("gemini");

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        DefaultTranslationService service = new DefaultTranslationService(
                List.of(bhashiniProvider, cloudProvider, geminiProvider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());

        // Translate with Gemini selected
        TranslationResult result = service.translate("test content", SupportedLanguages.ENGLISH, HINDI);

        // Gemini handled it, existing providers untouched
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(bhashiniProvider.invocationCount()).isZero();
        assertThat(cloudProvider.invocationCount()).isZero();
    }
}
