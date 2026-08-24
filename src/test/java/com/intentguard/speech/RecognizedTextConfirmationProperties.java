// Feature: indian-language-translation, Property 12: Recognized text is always offered for confirmation before session open
package com.intentguard.speech;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 12: Recognized text is always offered for
 * confirmation before session open.
 *
 * <p>For any recognized text and any recognition confidence value, the {@code Control_Tower}
 * presents the recognized text to the Operator for confirmation before an {@code Intent_Session} is
 * opened (Req 4.2, 4.6). This exercises {@link DefaultSpeechService#recognize} with a success
 * {@link SpeechProvider} fake that returns recognized text regardless of the confidence it is
 * constructed with, over the full double range of confidence values (including {@code 0.0} and
 * {@code 1.0}).
 *
 * <p>Confidence is deliberately <em>not modeled</em> in the recognition API: {@link RecognizedText}
 * carries no confidence field and {@link SpeechProvider#recognize} returns an
 * {@code Optional<RecognizedText>} with no confidence channel. That is the design realization of
 * Req 4.6 ("accept the recognized text for confirmation regardless of the recognition
 * confidence"). This property documents and asserts that acceptance holds for every recognized text
 * value and cannot be gated by any confidence value: the confidence generated below is threaded
 * through the fake provider but can never influence the outcome.
 *
 * <p>The property also asserts the Req 4.2 boundary: {@link SpeechService#recognize} yields only a
 * {@link SpeechRecognitionResult} carrying the recognized text for confirmation. The service has no
 * dependency on, and no reference to, any session manager, so recognition can never itself open an
 * {@code Intent_Session}; the session is opened downstream only after the Operator confirms the
 * presented text.
 *
 * <p>Validates: Requirements 4.2, 4.6.
 */
class RecognizedTextConfirmationProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();
    private final TechnicalTokenProtector tokenProtector = new TechnicalTokenProtector();

    // ---- Req 4.2, 4.6: recognized text is offered for confirmation regardless of confidence ------

    @Property(tries = 200)
    void recognizedTextIsAlwaysOfferedForConfirmationRegardlessOfConfidence(
            @ForAll("recognizedTexts") String recognizedText,
            @ForAll("confidences") double confidence,
            @ForAll("supportedTags") LanguageTag preference) {

        // A success provider that returns the recognized text; the confidence it is built with can
        // never affect the outcome because the recognition API carries no confidence channel (Req 4.6).
        SpeechProvider provider = new ConfidenceIgnoringSpeechProvider(recognizedText, confidence);
        DefaultSpeechService service = newService(provider);

        // Audio language matches the preference so the Req 4.5 gate passes and recognition proceeds.
        SpeechRecognitionResult result = service.recognize(anyAudio(), preference, preference);

        // Req 4.6: recognized regardless of confidence (including 0.0, 1.0, negative, out-of-range).
        assertThat(result.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
        assertThat(result.isRecognized()).isTrue();

        // Req 4.2: the recognized text is presented for confirmation, in the preference language.
        assertThat(result.text()).isPresent();
        RecognizedText offered = result.text().orElseThrow();
        assertThat(offered.text()).isEqualTo(recognizedText);
        assertThat(offered.language()).isEqualTo(preference);

        // Req 4.2 boundary: the call yields only recognized text for confirmation. The service can
        // never open an Intent_Session itself (it has no session-manager dependency); session open
        // happens downstream only after the Operator confirms this presented text.
        assertThat(result.providerId()).isEqualTo(provider.id());
    }

    // ---- Worked example: confidence 0.0 still yields text for confirmation ------------------------

    @Example
    void zeroConfidenceStillOffersTextForConfirmation() {
        SpeechProvider provider =
                new ConfidenceIgnoringSpeechProvider("restart the server", 0.0);
        DefaultSpeechService service = newService(provider);
        LanguageTag hindi = LanguageTag.of("hi");

        SpeechRecognitionResult result = service.recognize(anyAudio(), hindi, hindi);

        // Even at the lowest possible confidence the recognized text is offered for confirmation.
        assertThat(result.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
        assertThat(result.text()).isPresent();
        assertThat(result.text().orElseThrow().text()).isEqualTo("restart the server");
    }

    // ---- factory ---------------------------------------------------------------------------------

    private DefaultSpeechService newService(SpeechProvider provider) {
        return new DefaultSpeechService(
                SingleProvider.of(provider),
                new SpeechProperties(),
                supportedLanguages,
                tokenProtector);
    }

    private static AudioClip anyAudio() {
        return AudioClip.of("audio".getBytes(StandardCharsets.UTF_8), "audio/wav");
    }

    // ---- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> recognizedTexts() {
        // Arbitrary prose plus native Indian-script (non-ASCII, UTF-8) transcripts and the empty
        // string, so acceptance-for-confirmation is exercised across the full recognized-text space.
        Arbitrary<String> prose = Arbitraries.strings().ofMaxLength(60);
        Arbitrary<String> nativeScripts = Arbitraries.of(
                "restart the server",
                "\u0938\u0930\u094d\u0935\u0930 \u092a\u0941\u0928\u0930\u093e\u0930\u0902\u092d \u0915\u0930\u0947\u0902", // सर्वर पुनरारंभ करें
                "\u09b8\u09be\u09b0\u09cd\u09ad\u09be\u09b0 \u09aa\u09c1\u09a8\u09b0\u09be\u09af \u09b6\u09c1\u09b0\u09c1 \u0995\u09b0\u09c1\u09a8", // সার্ভার পুনরায শুরু করুন
                "\u0b9a\u0bc7\u0bb5\u0bbe\u0bb2\u0bb0\u0bc8 \u0bae\u0bb1\u0bc1\u0ba4\u0bca\u0b9f\u0b95\u0bcd\u0b95\u0bae\u0bcd", // சேவாலரை மறுதொடக்கம்
                "\u0c95\u0ca8\u0ccd\u0ca8\u0ca1 \u0caa\u0cbe\u0ca0",  // ಕನ್ನಡ ಪಾಠ
                "");
        return Arbitraries.oneOf(prose, nativeScripts);
    }

    @Provide
    Arbitrary<Double> confidences() {
        // The whole double range plus the endpoints 0.0 and 1.0 and out-of-range / special values,
        // to prove no confidence value can gate acceptance for confirmation (Req 4.6).
        Arbitrary<Double> fullRange = Arbitraries.doubles();
        Arbitrary<Double> edges = Arbitraries.of(
                0.0,
                1.0,
                -1.0,
                0.5,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY);
        return Arbitraries.oneOf(fullRange, edges);
    }

    @Provide
    Arbitrary<LanguageTag> supportedTags() {
        // The full configured Supported_Language set, including English (Req 6.2).
        return Arbitraries.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }

    // ---- test doubles ----------------------------------------------------------------------------

    /**
     * A success {@link SpeechProvider} fake that returns the configured recognized text in the
     * requested language. It is constructed with a confidence value to model a provider reporting an
     * arbitrary confidence, but that value can never influence the result: the recognition API
     * carries no confidence channel, which is exactly the Req 4.6 guarantee under test.
     */
    private static final class ConfidenceIgnoringSpeechProvider implements SpeechProvider {

        private final String transcript;
        @SuppressWarnings("unused")
        private final double confidence; // retained to document confidence is never gated (Req 4.6)

        ConfidenceIgnoringSpeechProvider(String transcript, double confidence) {
            this.transcript = transcript;
            this.confidence = confidence;
        }

        @Override
        public String id() {
            return "fake-confidence-speech";
        }

        @Override
        public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
            // Recognition succeeds regardless of confidence; RecognizedText carries no confidence.
            return Optional.of(new RecognizedText(transcript, language));
        }

        @Override
        public Optional<AudioClip> synthesize(String text, LanguageTag language) {
            return Optional.empty();
        }
    }

    /**
     * Minimal {@link ObjectProvider} test double wrapping a single {@link SpeechProvider}, wiring the
     * fake into {@link DefaultSpeechService} exactly as Spring would.
     */
    private static final class SingleProvider<T> implements ObjectProvider<T> {

        private final T instance;

        private SingleProvider(T instance) {
            this.instance = instance;
        }

        static <T> SingleProvider<T> of(T instance) {
            return new SingleProvider<>(instance);
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
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
