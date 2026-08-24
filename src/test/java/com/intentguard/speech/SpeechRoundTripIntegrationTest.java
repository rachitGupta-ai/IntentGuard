package com.intentguard.speech;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;

/**
 * Integration-style tests exercising real speech-to-text (STT) and text-to-speech (TTS) round trips
 * through {@link DefaultSpeechService} against a believable stub {@link SpeechProvider}.
 *
 * <p>Unlike the property tests (which stress the language-acceptance and confirmation invariants) and
 * the timeout/error unit tests (which pin each failure branch), these examples verify the happy-path
 * wiring end to end: the stub {@link RoundTripSpeechProvider} models an actual codec — {@code recognize}
 * decodes the audio bytes back into text in the requested language, and {@code synthesize} encodes text
 * into audio bytes — so a {@code synthesize} &rarr; {@code recognize} round trip reconstructs the
 * original content. This lets the tests assert:
 *
 * <ul>
 *   <li>recognizing audio produces recognized text in the Operator's {@code Language_Preference}
 *       (Req 4.1);</li>
 *   <li>synthesizing displayed content produces audio (Req 5.1);</li>
 *   <li>a synthesize &rarr; recognize round trip is coherent (the content survives the trip).</li>
 * </ul>
 *
 * <p>Validates: Requirements 4.1, 5.1.
 */
class SpeechRoundTripIntegrationTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");
    private static final LanguageTag TAMIL = LanguageTag.of("ta");

    /** A phrase containing a Technical_Token to keep the round trip realistic. */
    private static final String DISPLAYED_TEXT = "Run kubectl get pods on host web-01";

    // ---- STT: recognize audio to text (Req 4.1) --------------------------------------------------

    @Test
    void recognizeProducesTextInThePreferenceLanguage() {
        DefaultSpeechService service = newService(new RoundTripSpeechProvider());

        // Audio whose bytes encode a spoken phrase in the Operator's preferred language.
        String spokenPhrase = "मुझे web-01 पर पॉड्स देखने हैं";
        AudioClip audio = AudioClip.of(spokenPhrase.getBytes(StandardCharsets.UTF_8), "audio/wav");

        SpeechRecognitionResult result = service.recognize(audio, HINDI, HINDI);

        // Req 4.1: audio is converted to text in the matching Supported_Language and offered for
        // confirmation.
        assertThat(result.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
        assertThat(result.isRecognized()).isTrue();
        assertThat(result.text()).isPresent();
        assertThat(result.text().orElseThrow().language()).isEqualTo(HINDI);
        assertThat(result.text().orElseThrow().text()).isEqualTo(spokenPhrase);
        assertThat(result.providerId()).isEqualTo("round-trip-speech");
    }

    // ---- TTS: synthesize text to audio (Req 5.1) -------------------------------------------------

    @Test
    void synthesizeProducesAudioForDisplayedContent() {
        DefaultSpeechService service = newService(new RoundTripSpeechProvider());

        SpeechSynthesisResult result = service.synthesize(DISPLAYED_TEXT, TAMIL);

        // Req 5.1: displayed content is synthesized into audio in the Operator's Language_Preference.
        assertThat(result.outcome()).isEqualTo(TtsOutcome.SYNTHESIZED);
        assertThat(result.isSynthesized()).isTrue();
        assertThat(result.audioClip()).isPresent();
        assertThat(result.audioClip().orElseThrow().data()).isNotEmpty();
        assertThat(result.providerId()).isEqualTo("round-trip-speech");
    }

    // ---- Round trip: synthesize then recognize is coherent ---------------------------------------

    @Test
    void synthesizeThenRecognizeReconstructsTheOriginalContent() {
        DefaultSpeechService service = newService(new RoundTripSpeechProvider());

        // Listen: synthesize the displayed content into audio (Req 5.1).
        SpeechSynthesisResult synthesized = service.synthesize(DISPLAYED_TEXT, HINDI);
        assertThat(synthesized.isSynthesized()).isTrue();
        AudioClip audio = synthesized.audioClip().orElseThrow();

        // Speak: feed that audio back through recognition in the same language (Req 4.1).
        SpeechRecognitionResult recognized = service.recognize(audio, HINDI, HINDI);

        assertThat(recognized.isRecognized()).isTrue();
        // The round trip is coherent: the recognized text equals the originally synthesized content,
        // including the Technical_Token, and is tagged with the preference language.
        assertThat(recognized.text().orElseThrow().text()).isEqualTo(DISPLAYED_TEXT);
        assertThat(recognized.text().orElseThrow().language()).isEqualTo(HINDI);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static DefaultSpeechService newService(SpeechProvider provider) {
        return new DefaultSpeechService(
                new SingletonObjectProvider<>(provider),
                new SpeechProperties(),
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    /**
     * A believable stub {@link SpeechProvider} modeling a lossless audio codec: {@code synthesize}
     * encodes text to UTF-8 audio bytes and {@code recognize} decodes those bytes back into text in
     * the requested language. This makes a synthesize &rarr; recognize round trip reconstruct the
     * original content, without any network I/O. Following the {@code LlmService} contract it never
     * throws across the boundary.
     */
    private static final class RoundTripSpeechProvider implements SpeechProvider {

        static final String MIME_TYPE = "audio/wav";

        @Override
        public String id() {
            return "round-trip-speech";
        }

        @Override
        public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
            String text = new String(audio.data(), StandardCharsets.UTF_8);
            return Optional.of(new RecognizedText(text, language));
        }

        @Override
        public Optional<AudioClip> synthesize(String text, LanguageTag language) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return Optional.of(AudioClip.of(bytes, MIME_TYPE));
        }
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
