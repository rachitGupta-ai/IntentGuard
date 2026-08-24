package com.intentguard.api;

/**
 * Request body for {@code POST /api/speech/recognize}: spoken audio to be recognized into text for
 * operator confirmation before an Intent_Session is opened (Req 4.1).
 *
 * <p>{@code operatorId} identifies the Operator (the prototype accepts it in the body because the
 * endpoint is unauthenticated — see {@link SpeechController}); the controller resolves that
 * Operator's Language_Preference and passes it to the Speech_Service. {@code audio} carries the
 * Base64-encoded audio and its MIME type. {@code audioLanguageTag} is the BCP-47 tag the audio is
 * declared to be in; the Speech_Service accepts audio only for the language matching the Operator's
 * preference (Req 4.5).
 *
 * @param operatorId       the Operator submitting the audio
 * @param audio            the Base64-encoded audio clip
 * @param audioLanguageTag the BCP-47 tag the audio is declared to be in
 */
public record SpeechRecognizeRequest(
        String operatorId, AudioPayload audio, String audioLanguageTag) {
}
