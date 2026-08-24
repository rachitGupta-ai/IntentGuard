package com.intentguard.speech;

import java.util.Objects;

import com.intentguard.translation.LanguageTag;

/**
 * The text produced by speech-to-text (STT) recognition together with the {@code Supported_Language}
 * it was recognized in (Req 4.1).
 *
 * <p>The recognized text is accepted for confirmation regardless of recognition confidence
 * (Req 4.6), so no confidence value is carried here; the {@code SpeechService} presents this text
 * to the Operator for confirmation before an {@code Intent_Session} opens.
 *
 * @param text     the recognized text in {@code language} (never {@code null})
 * @param language the {@code Supported_Language} the audio was recognized in (never {@code null})
 */
public record RecognizedText(String text, LanguageTag language) {

    public RecognizedText {
        Objects.requireNonNull(text, "recognized text must not be null");
        Objects.requireNonNull(language, "recognized language must not be null");
    }
}
