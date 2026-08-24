package com.intentguard.api;

/**
 * A JSON-friendly carrier for an audio clip exchanged with the speech endpoints: the raw bytes
 * encoded as Base64 plus the audio MIME type. Base64 is used so audio can travel in a JSON body;
 * {@link SpeechController} decodes/encodes it into a {@code AudioClip}.
 *
 * <p>To avoid leaking spoken input, audio bytes carried here are never logged in the clear (Req 11).
 *
 * @param base64   the Base64-encoded audio bytes
 * @param mimeType the audio MIME type, for example {@code "audio/wav"}
 */
public record AudioPayload(String base64, String mimeType) {
}
