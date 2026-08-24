package com.intentguard.translation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.intentguard.speech.SpeechProperties;

/**
 * Unit tests for Translation_Provider / Speech_Provider hot-reload without a restart (Req 8.3).
 *
 * <p>{@link TranslationRuntimeConfig} holds the active runtime configuration in an
 * {@code AtomicReference} and swaps a validated {@link TranslationRuntimeConfig.Snapshot} in place on
 * {@link TranslationRuntimeConfig#applyUpdate(TranslationRuntimeUpdate)}. These tests assert that:
 * <ol>
 *   <li>an update to the active configuration is reflected by {@link TranslationRuntimeConfig#getActive()}
 *       immediately, with {@code null} update fields retaining their prior values;</li>
 *   <li>a subsequent {@link DefaultTranslationService#translate} request selects the newly configured
 *       Translation_Provider without any restart — demonstrated with two distinct provider fakes; and</li>
 *   <li>an invalid update (blank provider id or non-positive timeout) is rejected and the previously
 *       active snapshot is retained unchanged.</li>
 * </ol>
 */
class ProviderHotReloadTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");

    @Test
    void updateIsReflectedImmediatelyAndNullFieldsRetainPriorValues() {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider("provider-a");
        translationProperties.setTimeoutMs(2000);
        translationProperties.setSensitiveContentTranslatable(false);
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setProvider("speech-a");
        speechProperties.setSttTimeoutMs(10000);
        speechProperties.setTtsTimeoutMs(5000);

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        TranslationRuntimeConfig.Snapshot before = runtimeConfig.getActive();
        assertThat(before.translationProviderId()).isEqualTo("provider-a");
        assertThat(before.translationTimeoutMs()).isEqualTo(2000);
        assertThat(before.sensitiveContentTranslatable()).isFalse();
        assertThat(before.speechProviderId()).isEqualTo("speech-a");
        assertThat(before.sttTimeoutMs()).isEqualTo(10000);
        assertThat(before.ttsTimeoutMs()).isEqualTo(5000);

        // Change only the translation provider id, its timeout, and the sensitive-content policy;
        // every other field is left null so it must retain its prior value.
        TranslationRuntimeConfig.Snapshot after = runtimeConfig.applyUpdate(new TranslationRuntimeUpdate(
                "provider-b", 3000L, Boolean.TRUE, null, null, null));

        // The new configuration takes effect immediately (no restart) ...
        assertThat(after.translationProviderId()).isEqualTo("provider-b");
        assertThat(after.translationTimeoutMs()).isEqualTo(3000);
        assertThat(after.sensitiveContentTranslatable()).isTrue();
        // ... and getActive() returns the newly applied snapshot right away.
        assertThat(runtimeConfig.getActive()).isEqualTo(after);

        // Unspecified (null) fields retain their prior values.
        assertThat(after.speechProviderId()).isEqualTo("speech-a");
        assertThat(after.sttTimeoutMs()).isEqualTo(10000);
        assertThat(after.ttsTimeoutMs()).isEqualTo(5000);
    }

    @Test
    void subsequentRequestsUseTheNewlySelectedProviderWithoutRestart() {
        PassthroughTranslationProvider providerA = new PassthroughTranslationProvider("provider-a");
        PassthroughTranslationProvider providerB = new PassthroughTranslationProvider("provider-b");

        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(providerA.id());
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        DefaultTranslationService service = new DefaultTranslationService(
                List.of(providerA, providerB),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());

        // With provider A active, a translation request is served by provider A alone.
        TranslationResult first = service.translate("please review host one", SupportedLanguages.ENGLISH, HINDI);
        assertThat(first.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(providerA.invocationCount()).isEqualTo(1);
        assertThat(providerB.invocationCount()).isZero();

        // Hot-reload the runtime config to switch the active Translation_Provider to B; no restart.
        runtimeConfig.applyUpdate(new TranslationRuntimeUpdate(
                providerB.id(), null, null, null, null, null));

        // A subsequent request (fresh Source_Text to avoid the reuse cache) is now served by
        // provider B, proving the new configuration applies to subsequent requests immediately.
        TranslationResult second = service.translate("please review host two", SupportedLanguages.ENGLISH, HINDI);
        assertThat(second.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(providerB.invocationCount()).isEqualTo(1);
        // Provider A was not invoked again by the second request.
        assertThat(providerA.invocationCount()).isEqualTo(1);
    }

    @Test
    void invalidUpdateWithBlankProviderIdIsRejectedAndPreviousSnapshotRetained() {
        TranslationRuntimeConfig runtimeConfig = newRuntimeConfig();
        TranslationRuntimeConfig.Snapshot before = runtimeConfig.getActive();

        assertThatThrownBy(() -> runtimeConfig.applyUpdate(new TranslationRuntimeUpdate(
                "   ", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        // The rejected update leaves the previously active snapshot unchanged.
        assertThat(runtimeConfig.getActive()).isEqualTo(before);
    }

    @Test
    void invalidUpdateWithNonPositiveTimeoutIsRejectedAndPreviousSnapshotRetained() {
        TranslationRuntimeConfig runtimeConfig = newRuntimeConfig();
        TranslationRuntimeConfig.Snapshot before = runtimeConfig.getActive();

        assertThatThrownBy(() -> runtimeConfig.applyUpdate(new TranslationRuntimeUpdate(
                null, 0L, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(runtimeConfig.getActive()).isEqualTo(before);
    }

    private static TranslationRuntimeConfig newRuntimeConfig() {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider("provider-a");
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();
        return runtimeConfig;
    }
}
