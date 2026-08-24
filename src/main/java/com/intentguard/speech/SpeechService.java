package com.intentguard.speech;

import com.intentguard.translation.LanguageTag;

/**
 * The IntentGuard-side orchestrator for speech-to-text (STT) and text-to-speech (TTS), delegating
 * to a pluggable {@link SpeechProvider} (Req 8.2). Like {@code TranslationService}, it is pure
 * orchestration around the provider so it is unit- and property-testable with in-memory fakes.
 *
 * <p>Both operations follow the feature-wide <em>fail to English / never block the operator</em>
 * rule: provider timeouts and errors degrade to a localized message (STT) or to presenting the
 * content as text (TTS) rather than throwing. Every provider call is bounded by the configured
 * timeout (STT 10s, TTS 5s) so the {@code Control_Tower} is never blocked on speech. All audio and
 * text are transmitted to the {@code Speech_Provider} only over encrypted transport (Req 11.2),
 * which the provider adapters enforce.
 *
 * <p>Unlike the raw {@link SpeechProvider} operations (which return {@code Optional}), these methods
 * return richer result types ({@link SpeechRecognitionResult}, {@link SpeechSynthesisResult}) that
 * capture the timeout/error/rejection outcomes so the controller and unit tests can assert each
 * Req 4.x / 5.x branch.
 */
public interface SpeechService {

    /**
     * Recognizes spoken audio into text (STT, Req 4.1). Audio is accepted only for the language
     * matching the Operator's {@code Language_Preference}; audio whose language differs is rejected
     * without being sent to the provider (Req 4.5). Recognized text is returned for confirmation
     * regardless of recognition confidence (Req 4.6). A recognition timeout discards the audio with
     * a retry prompt (Req 4.3); a provider error yields a localized failure message (Req 4.4).
     *
     * @param audio         the audio clip to recognize
     * @param audioLanguage the {@code Supported_Language} the submitted audio is declared to be in
     * @param preference    the Operator's {@code Language_Preference}
     * @return a {@link SpeechRecognitionResult} carrying the outcome and either recognized text or a
     *         localized message; never {@code null}
     */
    SpeechRecognitionResult recognize(AudioClip audio, LanguageTag audioLanguage, LanguageTag preference);

    /**
     * Synthesizes an item of already-displayed {@code Operator_Facing_Content} into audio in the
     * Operator's {@code Language_Preference} (TTS, Req 5.1). The text supplied to the provider is
     * byte-for-byte the displayed content, so every {@code Technical_Token} is unchanged (Req 5.2),
     * reusing the {@code TechnicalTokenProtector} guarantee. A synthesis timeout (Req 5.3), a
     * provider error within the budget (Req 5.4), or a timeout with an error (Req 5.5) all present
     * the content as text; the outcome distinguishes what is recorded.
     *
     * @param displayedText the displayed content to read aloud
     * @param preference    the Operator's {@code Language_Preference}
     * @return a {@link SpeechSynthesisResult} carrying the outcome and either audio or the text to
     *         present; never {@code null}
     */
    SpeechSynthesisResult synthesize(String displayedText, LanguageTag preference);
}
