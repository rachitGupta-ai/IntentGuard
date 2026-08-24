package com.intentguard.speech;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.MaskedText;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;
import com.intentguard.translation.TranslationRuntimeConfig;

import jakarta.annotation.PreDestroy;

/**
 * Default {@link SpeechService} orchestrating speech-to-text (STT) and text-to-speech (TTS) around a
 * pluggable {@link SpeechProvider} (Req 4, Req 5, Req 8.2).
 *
 * <p>Each provider call is run on a bounded executor and awaited with the configured per-operation
 * timeout (STT {@code stt-timeout-ms}, default 10s; TTS {@code tts-timeout-ms}, default 5s), so the
 * {@code Control_Tower} is never blocked on speech. The three provider results are mapped to the
 * outcome types the requirements distinguish:
 *
 * <ul>
 *   <li>a returned value &rarr; success (recognized text / synthesized audio);</li>
 *   <li>an empty {@code Optional} within the budget &rarr; a provider <em>error</em>
 *       ({@link SttOutcome#ERROR} Req 4.4 / {@link TtsOutcome#SYNTHESIS_ERROR} Req 5.4);</li>
 *   <li>a timeout &rarr; discard + retry ({@link SttOutcome#TIMEOUT} Req 4.3) or playback
 *       unavailable ({@link TtsOutcome#PLAYBACK_UNAVAILABLE} Req 5.3), which also covers the
 *       timeout-and-error case (Req 5.5) because the elapsed budget is decisive.</li>
 * </ul>
 *
 * <p>The {@link SpeechProvider} dependency is optional so the application context still loads when
 * the speech credential is absent and no provider bean is registered (degraded mode, Req 8.5): STT
 * then reports a localized failure and TTS presents content as text.
 */
@Service
public class DefaultSpeechService implements SpeechService {

    private static final Logger log = System.getLogger(DefaultSpeechService.class.getName());

    private final ObjectProvider<SpeechProvider> speechProvider;
    private final SpeechProperties properties;
    private final SupportedLanguages supportedLanguages;
    private final TechnicalTokenProtector tokenProtector;
    // Startup-evaluated speech capability gate (Req 8.5, 8.6): true when the Speech_Provider
    // credential was present at startup. When false, STT and TTS degrade without touching the
    // provider so a missing speech credential disables only speech.
    private final BooleanSupplier speechEnabled;
    private final ExecutorService executor;

    /**
     * Direct-construction constructor used by unit, property, and integration tests that exercise
     * the STT/TTS orchestration without the Spring context. The startup speech-capability gate is
     * not the concern of direct-construction callers, so speech is treated as enabled and every call
     * is delegated to the supplied provider (mirrors {@link com.intentguard.translation.DefaultTranslationService}'s
     * test constructor, which disables persistence rather than capability gating).
     */
    public DefaultSpeechService(
            ObjectProvider<SpeechProvider> speechProvider,
            SpeechProperties properties,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector) {
        this(speechProvider, properties, supportedLanguages, tokenProtector, () -> true);
    }

    /**
     * Spring-injected constructor that additionally gates STT and TTS on the startup-evaluated
     * speech capability flag (Req 8.5, 8.6). {@link TranslationRuntimeConfig#isSpeechEnabled()} is
     * fixed at startup from the presence of a Speech_Provider credential, so a missing speech
     * credential disables only speech while text translation stays available when its own credential
     * is present, and a provider hot-reload can never re-enable a capability whose credential was
     * absent at startup.
     */
    @Autowired
    public DefaultSpeechService(
            ObjectProvider<SpeechProvider> speechProvider,
            SpeechProperties properties,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            TranslationRuntimeConfig runtimeConfig) {
        this(speechProvider, properties, supportedLanguages, tokenProtector,
                Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null")::isSpeechEnabled);
    }

    private DefaultSpeechService(
            ObjectProvider<SpeechProvider> speechProvider,
            SpeechProperties properties,
            SupportedLanguages supportedLanguages,
            TechnicalTokenProtector tokenProtector,
            BooleanSupplier speechEnabled) {
        this.speechProvider = Objects.requireNonNull(speechProvider, "speechProvider must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.supportedLanguages =
                Objects.requireNonNull(supportedLanguages, "supportedLanguages must not be null");
        this.tokenProtector =
                Objects.requireNonNull(tokenProtector, "tokenProtector must not be null");
        this.speechEnabled = Objects.requireNonNull(speechEnabled, "speechEnabled must not be null");
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory());
    }

    @Override
    public SpeechRecognitionResult recognize(
            AudioClip audio, LanguageTag audioLanguage, LanguageTag preference) {
        Objects.requireNonNull(audio, "audio must not be null");
        Objects.requireNonNull(audioLanguage, "audioLanguage must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        // Speech capability gate (Req 8.5, 8.6): when the Speech_Provider credential was absent at
        // startup, speech is disabled for the life of the process. Report a localized failure and
        // never contact a provider; text translation remains available independently.
        if (!speechEnabled.getAsBoolean()) {
            return SpeechRecognitionResult.error(SpeechMessages.recognitionFailed(preference), null);
        }

        // Req 4.5: accept audio only for the Supported_Language matching the Language_Preference.
        // Any mismatched or unsupported tag is rejected without contacting the provider.
        if (!supportedLanguages.isSupported(preference)
                || !supportedLanguages.isSupported(audioLanguage)
                || !audioLanguage.equals(preference)) {
            return SpeechRecognitionResult.languageRejected(SpeechMessages.languageMismatch(preference));
        }

        SpeechProvider provider = resolveProvider();
        if (provider == null) {
            // No provider configured (speech disabled at startup): localized failure (Req 4.4/8.5).
            return SpeechRecognitionResult.error(SpeechMessages.recognitionFailed(preference), null);
        }

        String providerId = provider.id();
        Bounded<Optional<RecognizedText>> outcome = runBounded(
                () -> provider.recognize(audio, preference), properties.getSttTimeoutMs());

        switch (outcome.status()) {
            case TIMEOUT:
                // Req 4.3: discard the audio and prompt the Operator to retry.
                return SpeechRecognitionResult.timeout(SpeechMessages.retryPrompt(preference), providerId);
            case COMPLETED:
                Optional<RecognizedText> recognized = outcome.value();
                if (recognized != null && recognized.isPresent()) {
                    // Req 4.1, 4.6: recognized text is offered for confirmation regardless of confidence.
                    return SpeechRecognitionResult.recognized(recognized.get(), providerId);
                }
                // Empty within budget => provider error (Req 4.4).
                return SpeechRecognitionResult.error(SpeechMessages.recognitionFailed(preference), providerId);
            case FAILED:
            default:
                return SpeechRecognitionResult.error(SpeechMessages.recognitionFailed(preference), providerId);
        }
    }

    @Override
    public SpeechSynthesisResult synthesize(String displayedText, LanguageTag preference) {
        Objects.requireNonNull(displayedText, "displayedText must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        // Speech capability gate (Req 8.5, 8.6): when the Speech_Provider credential was absent at
        // startup, speech is disabled for the life of the process. Present the content as text and
        // never contact a provider; text translation remains available independently.
        if (!speechEnabled.getAsBoolean()) {
            return SpeechSynthesisResult.playbackUnavailable(displayedText, null);
        }

        // Req 5.2: the text handed to the provider must be byte-for-byte the displayed content.
        // Reuse the TechnicalTokenProtector: masking then restoring reconstructs the exact original
        // bytes, so every Technical_Token supplied to synthesis is unchanged from what is displayed.
        String textForProvider = byteForByteContent(displayedText);

        if (!supportedLanguages.isSupported(preference)) {
            // Cannot synthesize in an unsupported language; present the content as text.
            return SpeechSynthesisResult.playbackUnavailable(displayedText, null);
        }

        SpeechProvider provider = resolveProvider();
        if (provider == null) {
            // No provider configured (speech disabled at startup): present as text (Req 5.3/8.5).
            return SpeechSynthesisResult.playbackUnavailable(displayedText, null);
        }

        String providerId = provider.id();
        Bounded<Optional<AudioClip>> outcome = runBounded(
                () -> provider.synthesize(textForProvider, preference), properties.getTtsTimeoutMs());

        switch (outcome.status()) {
            case TIMEOUT:
                // Req 5.3 (and Req 5.5 timeout-and-error): present as text, record playback unavailable.
                return SpeechSynthesisResult.playbackUnavailable(displayedText, providerId);
            case COMPLETED:
                Optional<AudioClip> audio = outcome.value();
                if (audio != null && audio.isPresent()) {
                    return SpeechSynthesisResult.synthesized(audio.get(), providerId);
                }
                // Empty within budget => provider error (Req 5.4).
                return SpeechSynthesisResult.synthesisError(displayedText, providerId);
            case FAILED:
            default:
                return SpeechSynthesisResult.synthesisError(displayedText, providerId);
        }
    }

    /**
     * Reconstructs the Source_Text byte-for-byte through the {@link TechnicalTokenProtector}
     * mask/restore round trip, guaranteeing every Technical_Token is preserved exactly before the
     * content is handed to synthesis (Req 5.2). The result equals {@code displayedText}.
     */
    private String byteForByteContent(String displayedText) {
        MaskedText masked = tokenProtector.mask(displayedText);
        String restored = tokenProtector.restore(masked.masked(), masked);
        if (!tokenProtector.allTokensPreserved(restored, masked)) {
            // Defensive: the round trip is identity, so this should never happen; fall back to the
            // original displayed content rather than any altered form.
            return displayedText;
        }
        return restored;
    }

    /** Resolves the configured {@link SpeechProvider}, or {@code null} when none is available. */
    private SpeechProvider resolveProvider() {
        try {
            return speechProvider.getIfAvailable();
        } catch (RuntimeException ambiguous) {
            // More than one provider registered but none uniquely resolvable: degrade safely.
            log.log(Level.DEBUG, "Could not uniquely resolve a Speech_Provider; degrading speech");
            return null;
        }
    }

    /**
     * Runs {@code call} on the bounded executor and awaits it for {@code timeoutMs}, classifying the
     * result as {@link Status#COMPLETED} (returned a value), {@link Status#TIMEOUT} (budget elapsed),
     * or {@link Status#FAILED} (interrupted or threw). The {@link SpeechProvider} contract is
     * never-throw, so {@code FAILED} is only reached on unexpected conditions.
     */
    private <T> Bounded<T> runBounded(Callable<T> call, long timeoutMs) {
        Future<T> future = executor.submit(call);
        try {
            T value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Bounded.completed(value);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return Bounded.timeout();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Bounded.failed();
        } catch (Exception error) {
            future.cancel(true);
            log.log(Level.DEBUG, "Speech provider call failed; degrading");
            return Bounded.failed();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "speech-service");
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum Status {
        COMPLETED, TIMEOUT, FAILED
    }

    /** The classified outcome of a bounded provider call. */
    private record Bounded<T>(Status status, T value) {

        static <T> Bounded<T> completed(T value) {
            return new Bounded<>(Status.COMPLETED, value);
        }

        static <T> Bounded<T> timeout() {
            return new Bounded<>(Status.TIMEOUT, null);
        }

        static <T> Bounded<T> failed() {
            return new Bounded<>(Status.FAILED, null);
        }
    }
}
