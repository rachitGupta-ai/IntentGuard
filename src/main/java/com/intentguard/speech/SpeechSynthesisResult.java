package com.intentguard.speech;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of a {@link SpeechService#synthesize} call, capturing the {@link TtsOutcome} together
 * with either the synthesized {@link AudioClip} (on success) or the text to present instead (on
 * timeout or error), so the controller and unit tests can assert each Req 5.x branch.
 *
 * <p>On any non-success outcome the {@code Control_Tower} presents {@link #presentedText()} as text
 * (Req 5.3, 5.4, 5.5); {@code audio} is present only when {@code outcome == SYNTHESIZED}.
 *
 * @param outcome       the synthesis outcome (never {@code null})
 * @param audio         the synthesized audio, present only when {@code outcome == SYNTHESIZED}
 * @param presentedText the text to present when audio is unavailable (the displayed content)
 * @param providerId    the {@code Speech_Provider} identity used (Req 8.7); may be empty when no
 *                      provider was invoked
 */
public record SpeechSynthesisResult(
        TtsOutcome outcome, AudioClip audio, String presentedText, String providerId) {

    public SpeechSynthesisResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** Audio synthesized successfully (Req 5.1). */
    public static SpeechSynthesisResult synthesized(AudioClip audio, String providerId) {
        return new SpeechSynthesisResult(
                TtsOutcome.SYNTHESIZED,
                Objects.requireNonNull(audio, "audio must not be null"),
                null,
                providerId);
    }

    /**
     * Timeout (Req 5.3) or timeout-and-error (Req 5.5): present content as text and record playback
     * unavailable.
     */
    public static SpeechSynthesisResult playbackUnavailable(String presentedText, String providerId) {
        return new SpeechSynthesisResult(
                TtsOutcome.PLAYBACK_UNAVAILABLE, null, presentedText, providerId);
    }

    /** Error within the timeout budget (Req 5.4): present content as text and record the error. */
    public static SpeechSynthesisResult synthesisError(String presentedText, String providerId) {
        return new SpeechSynthesisResult(
                TtsOutcome.SYNTHESIS_ERROR, null, presentedText, providerId);
    }

    /** Whether synthesis succeeded and audio is available for playback. */
    public boolean isSynthesized() {
        return outcome == TtsOutcome.SYNTHESIZED;
    }

    /** The synthesized audio if present. */
    public Optional<AudioClip> audioClip() {
        return Optional.ofNullable(audio);
    }
}
