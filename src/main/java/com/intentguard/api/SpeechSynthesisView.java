package com.intentguard.api;

import java.util.Base64;

import com.intentguard.speech.SpeechSynthesisResult;

/**
 * Response body for {@code POST /api/content/speech}: the classified synthesis outcome together
 * with either the synthesized audio (Base64) or the text to present instead (Req 5.1&ndash;5.5).
 *
 * <p>On {@code SYNTHESIZED} the {@code audioBase64} and {@code mimeType} are populated and
 * {@code presentedText} is {@code null}. On {@code PLAYBACK_UNAVAILABLE}/{@code SYNTHESIS_ERROR} the
 * audio fields are {@code null} and {@code presentedText} carries the content to present as text,
 * honouring the <em>present as text on failure</em> rule. {@code providerId} names the
 * Speech_Provider used, when one was invoked (Req 8.7).
 *
 * @param outcome       the {@code TtsOutcome} name
 * @param audioBase64   the Base64-encoded synthesized audio, or {@code null} on a non-success outcome
 * @param mimeType      the synthesized audio MIME type, or {@code null} on a non-success outcome
 * @param presentedText the text to present when audio is unavailable, or {@code null} on success
 * @param providerId    the Speech_Provider identity, or {@code null} when none was invoked
 */
public record SpeechSynthesisView(
        String outcome, String audioBase64, String mimeType, String presentedText, String providerId) {

    /** Builds a view from a {@link SpeechSynthesisResult}, Base64-encoding any synthesized audio. */
    public static SpeechSynthesisView from(SpeechSynthesisResult result) {
        if (result.isSynthesized()) {
            var audio = result.audioClip().orElseThrow();
            String base64 = Base64.getEncoder().encodeToString(audio.data());
            return new SpeechSynthesisView(
                    result.outcome().name(), base64, audio.mimeType(), null, result.providerId());
        }
        return new SpeechSynthesisView(
                result.outcome().name(), null, null, result.presentedText(), result.providerId());
    }
}
