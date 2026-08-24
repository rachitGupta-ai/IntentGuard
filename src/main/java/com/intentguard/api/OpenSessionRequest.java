package com.intentguard.api;

/**
 * Request body for {@code POST /api/sessions}: an operator's Declared_Intent submitted in a
 * Supported_Language, to be run through the inbound-text flow (Req 3.1).
 *
 * <p>The {@code declaredIntent} is the Source_Text exactly as typed/spoken by the operator (it may
 * be in a non-English Supported_Language); {@code sourceLanguageTag} is its BCP-47 tag (for example
 * {@code "hi"}). A {@code null}/blank {@code sourceLanguageTag} is treated as English so an English
 * submission opens the session directly. The prototype accepts {@code operatorId} in the body
 * because the endpoint is unauthenticated (see {@link IntentSessionController}); a blank value
 * falls back to a default operator.
 *
 * @param operatorId        the human operator submitting the intent (also the session user)
 * @param declaredIntent    the Declared_Intent Source_Text
 * @param sourceLanguageTag the BCP-47 language tag the intent was submitted in, or {@code null} for
 *                          English
 */
public record OpenSessionRequest(String operatorId, String declaredIntent, String sourceLanguageTag) {
}
