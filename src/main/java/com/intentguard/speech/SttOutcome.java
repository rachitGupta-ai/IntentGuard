package com.intentguard.speech;

/**
 * The outcome of a speech-to-text (STT) recognition request performed by the {@link SpeechService}
 * (Req 4.1&ndash;4.6). Callers (the controller and unit tests) switch on this to decide how to
 * present the result to the Operator.
 */
public enum SttOutcome {

    /**
     * Audio was recognized into text and is offered to the Operator for confirmation before an
     * {@code Intent_Session} opens, regardless of recognition confidence (Req 4.1, 4.6).
     */
    RECOGNIZED,

    /**
     * The audio's language did not match the Operator's {@code Language_Preference}, so the audio
     * was rejected without being sent to the {@code Speech_Provider} (Req 4.5).
     */
    LANGUAGE_REJECTED,

    /**
     * The {@code Speech_Provider} did not return recognized text within the configured 10s
     * speech-recognition timeout; the audio is discarded and the Operator is prompted to retry
     * (Req 4.3).
     */
    TIMEOUT,

    /**
     * The {@code Speech_Provider} returned an error within the timeout budget; a localized
     * speech-recognition-failed message is presented to the Operator (Req 4.4).
     */
    ERROR
}
