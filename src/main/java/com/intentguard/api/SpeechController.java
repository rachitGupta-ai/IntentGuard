package com.intentguard.api;

import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intentguard.speech.AudioClip;
import com.intentguard.speech.SpeechRecognitionResult;
import com.intentguard.speech.SpeechService;
import com.intentguard.speech.SpeechSynthesisResult;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;

/**
 * REST surface for speech input (STT) and output (TTS).
 *
 * <p>Exposes two Operator operations (task 12.2):
 * <ul>
 *   <li>{@code POST /api/speech/recognize} — recognizes submitted audio into text in the language
 *       matching the Operator's Language_Preference and returns that text <em>for confirmation</em>;
 *       it does <strong>not</strong> open an Intent_Session (recognition only yields text the
 *       operator must confirm before submission). Audio whose declared language differs from the
 *       preference is rejected (HTTP 422), a recognition timeout returns HTTP 504 with a retry
 *       prompt, and a provider error returns HTTP 502 with a localized failure message (Req 4.1,
 *       4.3, 4.4, 4.5, 4.6).</li>
 *   <li>{@code POST /api/content/speech} — synthesizes an item of already-displayed
 *       Operator_Facing_Content into audio in the Operator's Language_Preference, returning the
 *       Base64 audio on success or, on timeout/error, the content presented as text with the
 *       recorded {@code TtsOutcome}; because the content is always presented (as audio or text)
 *       this returns HTTP 200 in every case (Req 5.1&ndash;5.5).</li>
 * </ul>
 *
 * <p><strong>SECURITY — UNAUTHENTICATED PROTOTYPE ENDPOINTS.</strong> Like
 * {@link ControlTowerController} and {@link IntentSessionController}, these endpoints act on behalf
 * of an operator (recognizing spoken input and synthesizing content) but currently have <em>no
 * authentication or authorization</em> layer. This is acceptable only for the hackathon prototype.
 * Per the reference-monitor trust model, before any non-prototype use these endpoints MUST be
 * protected — e.g. bound to a loopback/OS-restricted interface owned by the {@code intentguard}
 * service account, and/or placed behind an authenticating filter (mTLS, signed admin token, or a
 * reverse proxy enforcing operator identity). Do not expose this controller on an untrusted network
 * as-is. To avoid leaking spoken input or content, this controller never logs the submitted audio,
 * recognized text, or synthesized content in the clear (Req 11).
 */
@RestController
@RequestMapping("/api")
public class SpeechController {

    private static final String DEFAULT_OPERATOR = "admin";

    private final SpeechService speechService;
    private final LanguagePreferenceService languagePreferenceService;

    public SpeechController(
            SpeechService speechService, LanguagePreferenceService languagePreferenceService) {
        this.speechService = speechService;
        this.languagePreferenceService = languagePreferenceService;
    }

    /**
     * Recognizes submitted audio into text for operator confirmation (Req 4.1). The Operator's
     * Language_Preference is resolved and passed to the Speech_Service, which accepts audio only for
     * the matching language (Req 4.5) and offers the recognized text for confirmation regardless of
     * confidence (Req 4.6). No Intent_Session is opened here. The HTTP status reflects the
     * {@code SttOutcome}: recognized &rarr; 200, language-mismatch &rarr; 422, timeout &rarr; 504
     * (retry prompt, Req 4.3), provider error &rarr; 502 (localized failure, Req 4.4).
     */
    @PostMapping("/speech/recognize")
    public ResponseEntity<SpeechRecognitionView> recognize(
            @RequestBody SpeechRecognizeRequest request) {
        String operatorId = resolveOperatorId(request.operatorId());
        LanguageTag preference = languagePreferenceService.getPreference(operatorId);
        LanguageTag audioLanguage = parseLanguage(request.audioLanguageTag(), "audioLanguageTag");
        AudioClip audio = decodeAudio(request.audio());

        SpeechRecognitionResult result =
                speechService.recognize(audio, audioLanguage, preference);
        SpeechRecognitionView body = SpeechRecognitionView.from(result);

        HttpStatus status = switch (result.outcome()) {
            case RECOGNIZED -> HttpStatus.OK;
            case LANGUAGE_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case ERROR -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Synthesizes an item of already-displayed Operator_Facing_Content into audio in the Operator's
     * Language_Preference (Req 5.1). The text supplied to the provider is byte-for-byte the displayed
     * content, so Technical_Tokens are unchanged (Req 5.2). On timeout/error the content is presented
     * as text with the recorded outcome (Req 5.3, 5.4, 5.5); because the content is always presented
     * (as audio or text), the response is HTTP 200 in every case.
     */
    @PostMapping("/content/speech")
    public ResponseEntity<SpeechSynthesisView> synthesizeContent(
            @RequestBody SynthesizeContentRequest request) {
        String operatorId = resolveOperatorId(request.operatorId());
        LanguageTag preference = languagePreferenceService.getPreference(operatorId);

        SpeechSynthesisResult result = speechService.synthesize(request.content(), preference);
        return ResponseEntity.ok(SpeechSynthesisView.from(result));
    }

    private static String resolveOperatorId(String operatorId) {
        return (operatorId == null || operatorId.isBlank()) ? DEFAULT_OPERATOR : operatorId;
    }

    private static LanguageTag parseLanguage(String tag, String field) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException(field + " must be provided");
        }
        return LanguageTag.of(tag);
    }

    private static AudioClip decodeAudio(AudioPayload payload) {
        if (payload == null || payload.base64() == null || payload.base64().isBlank()) {
            throw new IllegalArgumentException("audio must be provided");
        }
        if (payload.mimeType() == null || payload.mimeType().isBlank()) {
            throw new IllegalArgumentException("audio mimeType must be provided");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload.base64());
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("audio must be valid Base64");
        }
        return AudioClip.of(bytes, payload.mimeType());
    }
}
