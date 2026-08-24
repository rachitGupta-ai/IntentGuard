package com.intentguard.speech;

import java.util.Optional;

import com.intentguard.translation.LanguageTag;

/**
 * The pluggable {@code Speech_Provider} abstraction that performs speech recognition (STT) and
 * speech synthesis (TTS) for a {@code Supported_Language} (Req 8.2).
 *
 * <p>Concrete adapters (for example a Bhashini or generic cloud provider) are interchangeable by
 * configuration only. Following the established {@link com.intentguard.llm.LlmService} contract,
 * every operation is <em>best-effort</em> and <strong>never throws across the service boundary</strong>:
 * on timeout, error, or unavailable credentials an implementation returns {@link Optional#empty()}
 * so the {@code SpeechService} can degrade to presenting content as text rather than blocking the
 * Operator. All transmission to the provider occurs only over encrypted transport (Req 11.2).
 */
public interface SpeechProvider {

    /**
     * @return the stable identity of this provider, recorded per recognition/synthesis request
     *         (Req 8.7)
     */
    String id();

    /**
     * Recognizes spoken audio into text in the given {@code Supported_Language} (STT, Req 4.1).
     *
     * @param audio    the audio clip to recognize
     * @param language the {@code Supported_Language} the audio is expected to be in
     * @return the {@link RecognizedText}, or {@link Optional#empty()} on timeout or error
     */
    Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language);

    /**
     * Synthesizes the given text into spoken audio in the given {@code Supported_Language}
     * (TTS, Req 5.1). The caller supplies text whose Technical_Tokens are already byte-for-byte the
     * displayed content (Req 5.2); the provider does not alter the text.
     *
     * @param text     the text to synthesize
     * @param language the {@code Supported_Language} to synthesize in
     * @return the synthesized {@link AudioClip}, or {@link Optional#empty()} on timeout or error
     */
    Optional<AudioClip> synthesize(String text, LanguageTag language);
}
