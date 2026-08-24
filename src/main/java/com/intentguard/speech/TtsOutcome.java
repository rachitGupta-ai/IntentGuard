package com.intentguard.speech;

/**
 * The outcome of a text-to-speech (TTS) synthesis request performed by the {@link SpeechService}
 * (Req 5.1&ndash;5.5). On any non-success outcome the content is presented as text; the outcome
 * distinguishes what is recorded for the failure.
 */
public enum TtsOutcome {

    /** Audio was synthesized in the Operator's {@code Language_Preference} (Req 5.1). */
    SYNTHESIZED,

    /**
     * The {@code Speech_Provider} did not return audio within the configured 5s speech-synthesis
     * timeout (Req 5.3), or the timeout elapsed and the provider also errored for the same request
     * (Req 5.5). The content is presented as text and the failure is recorded as playback
     * unavailable.
     */
    PLAYBACK_UNAVAILABLE,

    /**
     * The {@code Speech_Provider} returned an error within the timeout budget (Req 5.4). The content
     * is presented as text and the synthesis error is recorded.
     */
    SYNTHESIS_ERROR
}
