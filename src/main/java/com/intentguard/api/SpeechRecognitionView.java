package com.intentguard.api;

import com.intentguard.speech.SpeechRecognitionResult;

/**
 * Response body for {@code POST /api/speech/recognize}: the classified recognition outcome together
 * with either the recognized text (for operator confirmation) or a localized operator-facing
 * message (Req 4.1&ndash;4.6).
 *
 * <p>On {@code RECOGNIZED} the {@code recognizedText} and its {@code languageTag} are populated and
 * {@code message} is {@code null}; recognition only yields text for confirmation and does
 * <strong>not</strong> open a session. On {@code LANGUAGE_REJECTED}/{@code TIMEOUT}/{@code ERROR}
 * the {@code message} carries the localized prompt/failure text and the recognized fields are
 * {@code null}. {@code providerId} names the Speech_Provider used, when one was invoked (Req 8.7).
 *
 * @param outcome        the {@code SttOutcome} name
 * @param recognizedText the recognized text, or {@code null} on a non-success outcome
 * @param languageTag    the BCP-47 tag the text was recognized in, or {@code null}
 * @param message        the localized operator-facing message, or {@code null} on success
 * @param providerId     the Speech_Provider identity, or {@code null} when none was invoked
 */
public record SpeechRecognitionView(
        String outcome, String recognizedText, String languageTag, String message, String providerId) {

    /** Builds a view from a {@link SpeechRecognitionResult}. */
    public static SpeechRecognitionView from(SpeechRecognitionResult result) {
        String text = result.text().map(recognized -> recognized.text()).orElse(null);
        String languageTag =
                result.text().map(recognized -> recognized.language().value()).orElse(null);
        return new SpeechRecognitionView(
                result.outcome().name(),
                text,
                languageTag,
                result.messageText().orElse(null),
                result.providerId());
    }
}
