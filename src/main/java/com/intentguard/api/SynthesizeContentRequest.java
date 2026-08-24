package com.intentguard.api;

/**
 * Request body for {@code POST /api/content/speech}: an item of already-displayed
 * Operator_Facing_Content to be synthesized into audio in the Operator's Language_Preference
 * (Req 5.1).
 *
 * <p>{@code operatorId} identifies the Operator (the prototype accepts it in the body because the
 * endpoint is unauthenticated — see {@link SpeechController}); the controller resolves that
 * Operator's Language_Preference and passes it to the Speech_Service. {@code content} is the
 * displayed text to read aloud; the text supplied to the provider is byte-for-byte the displayed
 * content, so every Technical_Token is unchanged (Req 5.2).
 *
 * @param operatorId the Operator requesting playback
 * @param content    the displayed content to synthesize
 */
public record SynthesizeContentRequest(String operatorId, String content) {
}
