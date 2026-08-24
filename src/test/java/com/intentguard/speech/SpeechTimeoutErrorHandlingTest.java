package com.intentguard.speech;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;

/**
 * Unit tests for {@link DefaultSpeechService} speech-to-text (STT) and text-to-speech (TTS)
 * timeout and error handling.
 *
 * <p>These example-based tests complement the property tests by pinning each Req 4.x / 5.x failure
 * branch to a concrete outcome and a localized operator-facing message:
 *
 * <ul>
 *   <li>STT timeout &rarr; audio discarded, {@link SttOutcome#TIMEOUT} with a localized retry
 *       prompt (Req 4.3);</li>
 *   <li>STT provider error within budget &rarr; {@link SttOutcome#ERROR} with a localized
 *       "speech recognition failed" message (Req 4.4);</li>
 *   <li>TTS timeout &rarr; content presented as text, {@link TtsOutcome#PLAYBACK_UNAVAILABLE}
 *       (Req 5.3);</li>
 *   <li>TTS provider error within budget &rarr; content presented as text,
 *       {@link TtsOutcome#SYNTHESIS_ERROR} (Req 5.4);</li>
 *   <li>TTS timeout with an eventual provider error &rarr; recorded as playback unavailable
 *       (Req 5.5), because the elapsed budget is decisive.</li>
 * </ul>
 *
 * <p>Timeouts are forced with very small STT/TTS budgets and a hanging provider fake so the tests
 * run quickly rather than waiting for the production 10s / 5s budgets.
 *
 * <p>Validates: Requirements 4.3, 4.4, 5.3, 5.4, 5.5.
 */
class SpeechTimeoutErrorHandlingTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");
    private static final AudioClip AUDIO =
            AudioClip.of("spoken audio".getBytes(StandardCharsets.UTF_8), "audio/wav");
    private static final String DISPLAYED_TEXT = "Run kubectl get pods on host web-01";

    /** Small timeout that a hanging provider is guaranteed to exceed, keeping tests fast. */
    private static final long TINY_TIMEOUT_MS = 50L;

    // ---- STT timeout (Req 4.3) -------------------------------------------------------------------

    @Test
    void sttTimeoutDiscardsAudioAndPromptsRetry() {
        SpeechProperties properties = properties(TINY_TIMEOUT_MS, TINY_TIMEOUT_MS);
        DefaultSpeechService service = newService(new FakeTimeoutSpeechProvider(), properties);

        SpeechRecognitionResult result = service.recognize(AUDIO, HINDI, HINDI);

        // Audio discarded: no recognized text, TIMEOUT outcome (Req 4.3).
        assertThat(result.outcome()).isEqualTo(SttOutcome.TIMEOUT);
        assertThat(result.isRecognized()).isFalse();
        assertThat(result.text()).isEmpty();
        // Localized retry prompt in the Operator's Language_Preference (Req 4.3).
        assertThat(result.messageText()).contains(SpeechMessages.retryPrompt(HINDI));
        assertThat(result.providerId()).isEqualTo("fake-timeout-speech");
    }

    // ---- STT error within budget (Req 4.4) -------------------------------------------------------

    @Test
    void sttProviderErrorReturnsLocalizedFailureMessage() {
        SpeechProperties properties = properties(10_000L, 5_000L);
        DefaultSpeechService service = newService(new FakeErrorSpeechProvider(), properties);

        SpeechRecognitionResult result = service.recognize(AUDIO, HINDI, HINDI);

        // Empty result within the budget => provider error with a localized failure message (Req 4.4).
        assertThat(result.outcome()).isEqualTo(SttOutcome.ERROR);
        assertThat(result.isRecognized()).isFalse();
        assertThat(result.text()).isEmpty();
        assertThat(result.messageText()).contains(SpeechMessages.recognitionFailed(HINDI));
        assertThat(result.providerId()).isEqualTo("fake-error-speech");
    }

    // ---- TTS timeout (Req 5.3) -------------------------------------------------------------------

    @Test
    void ttsTimeoutPresentsContentAsTextAndRecordsPlaybackUnavailable() {
        SpeechProperties properties = properties(TINY_TIMEOUT_MS, TINY_TIMEOUT_MS);
        DefaultSpeechService service = newService(new FakeTimeoutSpeechProvider(), properties);

        SpeechSynthesisResult result = service.synthesize(DISPLAYED_TEXT, HINDI);

        // Timeout => present content as text, record playback unavailable (Req 5.3).
        assertThat(result.outcome()).isEqualTo(TtsOutcome.PLAYBACK_UNAVAILABLE);
        assertThat(result.isSynthesized()).isFalse();
        assertThat(result.audioClip()).isEmpty();
        assertThat(result.presentedText()).isEqualTo(DISPLAYED_TEXT);
        assertThat(result.providerId()).isEqualTo("fake-timeout-speech");
    }

    // ---- TTS error within budget (Req 5.4) -------------------------------------------------------

    @Test
    void ttsProviderErrorWithinBudgetPresentsContentAsTextAndRecordsSynthesisError() {
        SpeechProperties properties = properties(10_000L, 5_000L);
        DefaultSpeechService service = newService(new FakeErrorSpeechProvider(), properties);

        SpeechSynthesisResult result = service.synthesize(DISPLAYED_TEXT, HINDI);

        // Empty result within the budget => synthesis error, content presented as text (Req 5.4).
        assertThat(result.outcome()).isEqualTo(TtsOutcome.SYNTHESIS_ERROR);
        assertThat(result.isSynthesized()).isFalse();
        assertThat(result.audioClip()).isEmpty();
        assertThat(result.presentedText()).isEqualTo(DISPLAYED_TEXT);
        assertThat(result.providerId()).isEqualTo("fake-error-speech");
    }

    // ---- TTS timeout-and-error (Req 5.5) ---------------------------------------------------------

    @Test
    void ttsTimeoutWithEventualErrorIsRecordedAsPlaybackUnavailable() {
        // The hanging provider would ultimately return empty (an error) after its delay, but the
        // small TTS budget elapses first. The elapsed budget is decisive, so the failure is recorded
        // as playback unavailable rather than a synthesis error (Req 5.5).
        SpeechProperties properties = properties(TINY_TIMEOUT_MS, TINY_TIMEOUT_MS);
        DefaultSpeechService service =
                newService(new FakeTimeoutSpeechProvider("fake-timeout-speech", 2_000L), properties);

        SpeechSynthesisResult result = service.synthesize(DISPLAYED_TEXT, HINDI);

        assertThat(result.outcome()).isEqualTo(TtsOutcome.PLAYBACK_UNAVAILABLE);
        assertThat(result.presentedText()).isEqualTo(DISPLAYED_TEXT);
        assertThat(result.providerId()).isEqualTo("fake-timeout-speech");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static DefaultSpeechService newService(SpeechProvider provider, SpeechProperties properties) {
        return new DefaultSpeechService(
                new SingletonObjectProvider<>(provider),
                properties,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    private static SpeechProperties properties(long sttTimeoutMs, long ttsTimeoutMs) {
        SpeechProperties properties = new SpeechProperties();
        properties.setSttTimeoutMs(sttTimeoutMs);
        properties.setTtsTimeoutMs(ttsTimeoutMs);
        return properties;
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
