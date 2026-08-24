// Feature: indian-language-translation, Property 11: Speech-to-text accepts only the preference-matching language
package com.intentguard.speech;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 11: Speech-to-text accepts only the
 * preference-matching language.
 *
 * <p>For any language tag that differs from the Operator's {@code Language_Preference}, submitted
 * audio is rejected without being sent to the {@code Speech_Provider}; audio is accepted (recognized)
 * only for the language matching the preference (Req 4.5). This exercises {@link DefaultSpeechService}
 * against a <em>success</em> {@link SpeechProvider} fake whose invocations are counted, so the
 * property can assert both that mismatched audio is rejected without invoking the provider and that
 * matching audio is accepted and offered for confirmation.
 *
 * <p>Validates: Requirements 4.5.
 */
class SpeechLanguageAcceptanceProperties {

    private static final AudioClip AUDIO =
            AudioClip.of("spoken audio".getBytes(StandardCharsets.UTF_8), "audio/wav");

    // ---- Property 11: mismatched audio rejected, matching audio accepted -------------------------

    @Property(tries = 200)
    void audioAcceptedOnlyForPreferenceMatchingLanguage(
            @ForAll("supportedTags") LanguageTag preference,
            @ForAll("supportedTags") LanguageTag audioLanguage) {

        CountingSpeechProvider provider = new CountingSpeechProvider();
        DefaultSpeechService service = newService(provider);

        SpeechRecognitionResult result = service.recognize(AUDIO, audioLanguage, preference);

        if (audioLanguage.equals(preference)) {
            // Matching language: the audio is accepted and recognized text offered for confirmation.
            assertThat(result.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
            assertThat(result.isRecognized()).isTrue();
            assertThat(provider.recognizeCount()).isEqualTo(1);
        } else {
            // Any differing tag is rejected without ever contacting the Speech_Provider (Req 4.5).
            assertThat(result.outcome()).isEqualTo(SttOutcome.LANGUAGE_REJECTED);
            assertThat(result.isRecognized()).isFalse();
            assertThat(provider.recognizeCount()).isZero();
        }
    }

    @Property(tries = 200)
    void audioForADifferentSupportedLanguageIsAlwaysRejected(
            @ForAll("supportedTags") LanguageTag preference,
            @ForAll("supportedTags") LanguageTag audioLanguage) {

        // Constrain to the strictly-mismatched case to focus on rejection without provider contact.
        Assume.that(!audioLanguage.equals(preference));

        CountingSpeechProvider provider = new CountingSpeechProvider();
        DefaultSpeechService service = newService(provider);

        SpeechRecognitionResult result = service.recognize(AUDIO, audioLanguage, preference);

        assertThat(result.outcome()).isEqualTo(SttOutcome.LANGUAGE_REJECTED);
        assertThat(provider.recognizeCount()).isZero();
    }

    // ---- Worked examples: concrete matching and mismatched pairs --------------------------------

    @Example
    void hindiAudioAcceptedForHindiPreference() {
        CountingSpeechProvider provider = new CountingSpeechProvider();
        DefaultSpeechService service = newService(provider);

        SpeechRecognitionResult result =
                service.recognize(AUDIO, LanguageTag.of("hi"), LanguageTag.of("hi"));

        assertThat(result.outcome()).isEqualTo(SttOutcome.RECOGNIZED);
        assertThat(result.text()).isPresent();
        assertThat(result.text().orElseThrow().language()).isEqualTo(LanguageTag.of("hi"));
        assertThat(provider.recognizeCount()).isEqualTo(1);
    }

    @Example
    void tamilAudioRejectedForHindiPreferenceWithoutProviderCall() {
        CountingSpeechProvider provider = new CountingSpeechProvider();
        DefaultSpeechService service = newService(provider);

        SpeechRecognitionResult result =
                service.recognize(AUDIO, LanguageTag.of("ta"), LanguageTag.of("hi"));

        assertThat(result.outcome()).isEqualTo(SttOutcome.LANGUAGE_REJECTED);
        assertThat(provider.recognizeCount()).isZero();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static DefaultSpeechService newService(SpeechProvider provider) {
        return new DefaultSpeechService(
                new SingletonObjectProvider<>(provider),
                new SpeechProperties(),
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    @Provide
    Arbitrary<LanguageTag> supportedTags() {
        return Arbitraries.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }

    /**
     * A success {@link SpeechProvider} that recognizes any audio into a fixed transcript in the
     * requested language, counting how many times it is invoked so the property can prove that
     * rejected (mismatched-language) audio never reaches the provider.
     */
    private static final class CountingSpeechProvider implements SpeechProvider {

        private final AtomicInteger recognizeCount = new AtomicInteger();

        @Override
        public String id() {
            return "counting-success-speech";
        }

        @Override
        public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
            recognizeCount.incrementAndGet();
            return Optional.of(new RecognizedText("recognized speech", language));
        }

        @Override
        public Optional<AudioClip> synthesize(String text, LanguageTag language) {
            return Optional.of(AudioClip.of(text.getBytes(StandardCharsets.UTF_8), "audio/wav"));
        }

        int recognizeCount() {
            return recognizeCount.get();
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
