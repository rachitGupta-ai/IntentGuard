package com.intentguard.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.intentguard.speech.AudioClip;
import com.intentguard.speech.RecognizedText;
import com.intentguard.speech.SpeechRecognitionResult;
import com.intentguard.speech.SpeechService;
import com.intentguard.speech.SpeechSynthesisResult;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;

/**
 * Unit tests for {@link SpeechController} using mocked collaborators (Req 4.1, 4.3, 4.4, 4.5, 5.1,
 * 5.3). The controller methods are exercised directly with a mocked {@link SpeechService} and
 * {@link LanguagePreferenceService} so the tests stay fast and require no Spring context or MongoDB.
 */
class SpeechControllerTest {

    private SpeechService speechService;
    private LanguagePreferenceService languagePreferenceService;
    private SpeechController controller;

    @BeforeEach
    void setUp() {
        speechService = mock(SpeechService.class);
        languagePreferenceService = mock(LanguagePreferenceService.class);
        controller = new SpeechController(speechService, languagePreferenceService);
    }

    private static AudioPayload wav(String marker) {
        String base64 = Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8));
        return new AudioPayload(base64, "audio/wav");
    }

    @Test
    void recognizeResolvesPreferenceAndReturnsRecognizedTextForConfirmation() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        String hindi = "\u092b\u093c\u093e\u0907\u0932 \u0939\u091f\u093e\u090f\u0902"; // recognized text
        when(speechService.recognize(any(AudioClip.class), eq(LanguageTag.of("hi")), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechRecognitionResult.recognized(
                        new RecognizedText(hindi, LanguageTag.of("hi")), "bhashini"));

        ResponseEntity<SpeechRecognitionView> response = controller.recognize(
                new SpeechRecognizeRequest("alice", wav("audio-bytes"), "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("RECOGNIZED");
        assertThat(response.getBody().recognizedText()).isEqualTo(hindi);
        assertThat(response.getBody().languageTag()).isEqualTo("hi");
        assertThat(response.getBody().providerId()).isEqualTo("bhashini");
        verify(speechService)
                .recognize(any(AudioClip.class), eq(LanguageTag.of("hi")), eq(LanguageTag.of("hi")));
    }

    @Test
    void recognizeLanguageMismatchReturns422() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        when(speechService.recognize(any(AudioClip.class), eq(LanguageTag.of("bn")), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechRecognitionResult.languageRejected("language mismatch"));

        ResponseEntity<SpeechRecognitionView> response = controller.recognize(
                new SpeechRecognizeRequest("alice", wav("audio-bytes"), "bn"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("LANGUAGE_REJECTED");
        assertThat(response.getBody().recognizedText()).isNull();
        assertThat(response.getBody().message()).isEqualTo("language mismatch");
    }

    @Test
    void recognizeTimeoutReturns504WithRetryPrompt() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        when(speechService.recognize(any(AudioClip.class), eq(LanguageTag.of("hi")), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechRecognitionResult.timeout("please retry", "bhashini"));

        ResponseEntity<SpeechRecognitionView> response = controller.recognize(
                new SpeechRecognizeRequest("alice", wav("audio-bytes"), "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("TIMEOUT");
        assertThat(response.getBody().message()).isEqualTo("please retry");
    }

    @Test
    void recognizeProviderErrorReturns502WithLocalizedMessage() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        when(speechService.recognize(any(AudioClip.class), eq(LanguageTag.of("hi")), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechRecognitionResult.error("recognition failed", "bhashini"));

        ResponseEntity<SpeechRecognitionView> response = controller.recognize(
                new SpeechRecognizeRequest("alice", wav("audio-bytes"), "hi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("ERROR");
        assertThat(response.getBody().message()).isEqualTo("recognition failed");
    }

    @Test
    void synthesizeContentReturnsBase64AudioOnSuccess() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        byte[] audioBytes = "synth-audio".getBytes(StandardCharsets.UTF_8);
        when(speechService.synthesize(eq("Risk score 0.91 for /etc/passwd"), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechSynthesisResult.synthesized(
                        AudioClip.of(audioBytes, "audio/mpeg"), "bhashini"));

        ResponseEntity<SpeechSynthesisView> response = controller.synthesizeContent(
                new SynthesizeContentRequest("alice", "Risk score 0.91 for /etc/passwd"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("SYNTHESIZED");
        assertThat(response.getBody().mimeType()).isEqualTo("audio/mpeg");
        assertThat(response.getBody().audioBase64())
                .isEqualTo(Base64.getEncoder().encodeToString(audioBytes));
        assertThat(response.getBody().presentedText()).isNull();
    }

    @Test
    void synthesizeContentTimeoutPresentsContentAsText() {
        when(languagePreferenceService.getPreference("alice")).thenReturn(LanguageTag.of("hi"));
        when(speechService.synthesize(eq("alert text"), eq(LanguageTag.of("hi"))))
                .thenReturn(SpeechSynthesisResult.playbackUnavailable("alert text", "bhashini"));

        ResponseEntity<SpeechSynthesisView> response = controller.synthesizeContent(
                new SynthesizeContentRequest("alice", "alert text"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().outcome()).isEqualTo("PLAYBACK_UNAVAILABLE");
        assertThat(response.getBody().audioBase64()).isNull();
        assertThat(response.getBody().presentedText()).isEqualTo("alert text");
    }

    @Test
    void recognizeBlankOperatorFallsBackToDefaultPreference() {
        when(languagePreferenceService.getPreference("admin")).thenReturn(LanguageTag.of("en"));
        when(speechService.recognize(any(AudioClip.class), eq(LanguageTag.of("en")), eq(LanguageTag.of("en"))))
                .thenReturn(SpeechRecognitionResult.recognized(
                        new RecognizedText("list files", LanguageTag.of("en")), "cloud"));

        ResponseEntity<SpeechRecognitionView> response = controller.recognize(
                new SpeechRecognizeRequest("  ", wav("audio-bytes"), "en"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(languagePreferenceService).getPreference("admin");
    }
}
