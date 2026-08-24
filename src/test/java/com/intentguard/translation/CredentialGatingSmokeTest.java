package com.intentguard.translation;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.speech.AudioClip;
import com.intentguard.speech.DefaultSpeechService;
import com.intentguard.speech.FakeSuccessSpeechProvider;
import com.intentguard.speech.SpeechProperties;
import com.intentguard.speech.SpeechProvider;
import com.intentguard.speech.SpeechRecognitionResult;
import com.intentguard.speech.SttOutcome;

/**
 * Smoke tests for startup credential-presence gating and per-capability enablement on
 * {@link TranslationRuntimeConfig} (Req 8.4, 8.5, 8.6).
 *
 * <p>{@link TranslationRuntimeConfig} evaluates Translation_Provider and Speech_Provider credential
 * presence exactly once, in {@link TranslationRuntimeConfig#initialize()}, from
 * {@link TranslationProperties#hasApiKey()} and {@link SpeechProperties#hasApiKey()}. These tests
 * pin the three requirements that flow from that:
 *
 * <ul>
 *   <li><strong>Req 8.4:</strong> a missing translation credential disables only text translation
 *       (content presented in English, the provider is never invoked) while speech stays enabled
 *       when its own credential is present.</li>
 *   <li><strong>Req 8.5:</strong> a missing speech credential disables only speech while text
 *       translation stays enabled when its own credential is present.</li>
 *   <li><strong>Req 8.6:</strong> credential presence is evaluated only at startup — mutating the
 *       bound properties after {@link TranslationRuntimeConfig#initialize()} does not change the
 *       capability flags.</li>
 * </ul>
 *
 * <p>Each config is built by constructing {@link TranslationRuntimeConfig} directly around
 * {@link TranslationProperties} + {@link SpeechProperties} and invoking {@code initialize()}, the
 * same seam Spring uses via {@code @PostConstruct}.
 */
class CredentialGatingSmokeTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");
    private static final AudioClip AUDIO =
            AudioClip.of("spoken intent".getBytes(StandardCharsets.UTF_8), "audio/wav");

    // ---- Req 8.4: missing translation credential disables only text translation -----------------

    @Test
    void missingTranslationCredentialDisablesOnlyTextTranslation() {
        TranslationProperties translation = new TranslationProperties();
        translation.setApiKey("");          // credential absent => text translation disabled
        SpeechProperties speech = new SpeechProperties();
        speech.setApiKey("speech-key");     // credential present => speech stays enabled

        TranslationRuntimeConfig config = initializedConfig(translation, speech);

        // Only text translation is disabled; speech remains enabled (Req 8.4).
        assertThat(config.isTextTranslationEnabled()).isFalse();
        assertThat(config.isSpeechEnabled()).isTrue();

        // Text translation degrades to presenting English without ever invoking the provider.
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService translationService = new DefaultTranslationService(
                List.of(provider),
                new TranslationCache(),
                config,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());

        TranslationResult result = translationService.translate("shut down host web-01", HINDI, HINDI);

        assertThat(result.text()).isEqualTo("shut down host web-01");
        assertThat(result.translated()).isFalse();
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.ENGLISH_PASSTHROUGH);
        assertThat(provider.invocationCount()).isZero();

        // Speech, gated on the same startup-fixed flag, stays fully functional (Req 8.4).
        DefaultSpeechService speechService = new DefaultSpeechService(
                new SingletonObjectProvider<>(new FakeSuccessSpeechProvider()),
                speech,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector(),
                config);

        SpeechRecognitionResult recognition = speechService.recognize(AUDIO, HINDI, HINDI);
        assertThat(recognition.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
        assertThat(recognition.isRecognized()).isTrue();
    }

    // ---- Req 8.5: missing speech credential disables only speech ---------------------------------

    @Test
    void missingSpeechCredentialDisablesOnlySpeech() {
        TranslationProperties translation = new TranslationProperties();
        translation.setApiKey("translation-key"); // credential present => text stays enabled
        SpeechProperties speech = new SpeechProperties();
        speech.setApiKey("");                       // credential absent => speech disabled

        TranslationRuntimeConfig config = initializedConfig(translation, speech);

        // Only speech is disabled; text translation remains enabled (Req 8.5).
        assertThat(config.isSpeechEnabled()).isFalse();
        assertThat(config.isTextTranslationEnabled()).isTrue();

        // Speech degrades without contacting the provider: recognition reports a localized error.
        DefaultSpeechService speechService = new DefaultSpeechService(
                new SingletonObjectProvider<>(new FakeSuccessSpeechProvider()),
                speech,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector(),
                config);

        SpeechRecognitionResult recognition = speechService.recognize(AUDIO, HINDI, HINDI);
        assertThat(recognition.outcome()).isEqualTo(SttOutcome.ERROR);
        assertThat(recognition.isRecognized()).isFalse();
    }

    // ---- Req 8.6: credential presence is evaluated only at startup -------------------------------

    @Test
    void credentialPresenceIsEvaluatedOnlyAtStartup() {
        TranslationProperties translation = new TranslationProperties();
        translation.setApiKey("translation-key"); // present at startup
        SpeechProperties speech = new SpeechProperties();
        speech.setApiKey("");                       // absent at startup

        TranslationRuntimeConfig config = initializedConfig(translation, speech);

        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isFalse();

        // Mutate the bound properties AFTER initialize(): remove the translation credential and add
        // the speech credential. The startup-fixed flags must not change (Req 8.6).
        translation.setApiKey("");
        speech.setApiKey("speech-key");

        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isFalse();

        // A runtime provider update likewise cannot re-enable a capability whose credential was
        // absent at startup, nor disable one that was present (Req 8.6).
        config.applyUpdate(new TranslationRuntimeUpdate(
                "cloud", null, null, "cloud", null, null));

        assertThat(config.isTextTranslationEnabled()).isTrue();
        assertThat(config.isSpeechEnabled()).isFalse();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static TranslationRuntimeConfig initializedConfig(
            TranslationProperties translation, SpeechProperties speech) {
        TranslationRuntimeConfig config = new TranslationRuntimeConfig(translation, speech);
        config.initialize();
        return config;
    }

    /** Minimal {@link ObjectProvider} test double wrapping a single {@link SpeechProvider}. */
    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject() {
            if (instance == null) {
                throw new NoSuchBeanDefinitionException("no instance available");
            }
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return getObject();
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
