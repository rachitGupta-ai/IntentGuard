package com.intentguard.speech;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of a {@link SpeechService#recognize} call, capturing the {@link SttOutcome} together
 * with either the {@link RecognizedText} (on success) or a localized message (on rejection,
 * timeout, or error) so the controller and unit tests can assert each Req 4.x branch without
 * inspecting provider internals.
 *
 * @param outcome        the recognition outcome (never {@code null})
 * @param recognizedText the recognized text, present only when {@code outcome == RECOGNIZED}
 * @param message        a localized operator-facing message, present for
 *                       {@link SttOutcome#LANGUAGE_REJECTED}, {@link SttOutcome#TIMEOUT}, and
 *                       {@link SttOutcome#ERROR}; empty for a successful recognition
 * @param providerId     the {@code Speech_Provider} identity used (Req 8.7); empty when no provider
 *                       was invoked (for example a language-rejected request)
 */
public record SpeechRecognitionResult(
        SttOutcome outcome, RecognizedText recognizedText, String message, String providerId) {

    public SpeechRecognitionResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** A successful recognition offered for confirmation (Req 4.1, 4.6). */
    public static SpeechRecognitionResult recognized(RecognizedText text, String providerId) {
        return new SpeechRecognitionResult(
                SttOutcome.RECOGNIZED,
                Objects.requireNonNull(text, "recognized text must not be null"),
                null,
                providerId);
    }

    /** Audio rejected because its language did not match the preference (Req 4.5). */
    public static SpeechRecognitionResult languageRejected(String message) {
        return new SpeechRecognitionResult(SttOutcome.LANGUAGE_REJECTED, null, message, null);
    }

    /** Recognition timed out; audio discarded with a retry prompt (Req 4.3). */
    public static SpeechRecognitionResult timeout(String message, String providerId) {
        return new SpeechRecognitionResult(SttOutcome.TIMEOUT, null, message, providerId);
    }

    /** Provider error within the budget; localized failure message (Req 4.4). */
    public static SpeechRecognitionResult error(String message, String providerId) {
        return new SpeechRecognitionResult(SttOutcome.ERROR, null, message, providerId);
    }

    /** Whether recognition succeeded and text is available for confirmation. */
    public boolean isRecognized() {
        return outcome == SttOutcome.RECOGNIZED;
    }

    /** The recognized text if present. */
    public Optional<RecognizedText> text() {
        return Optional.ofNullable(recognizedText);
    }

    /** The localized operator-facing message if present. */
    public Optional<String> messageText() {
        return Optional.ofNullable(message);
    }
}
