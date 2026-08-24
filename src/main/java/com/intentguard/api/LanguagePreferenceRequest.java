package com.intentguard.api;

/**
 * Request body for {@code PUT /api/preferences/language}: an Operator's Language_Preference
 * selection (Req 1.1, 1.2).
 *
 * <p>{@code operatorId} identifies the Operator whose preference is being set; the prototype accepts
 * it in the body because the endpoint is unauthenticated (see {@link TranslationController}).
 * {@code languageTag} is the BCP-47 tag of the requested Supported_Language (for example
 * {@code "hi"}); a tag outside the configured Supported_Language set is rejected and the current
 * preference retained (Req 1.5).
 *
 * @param operatorId  the Operator whose preference to set
 * @param languageTag the BCP-47 tag of the requested Supported_Language
 */
public record LanguagePreferenceRequest(String operatorId, String languageTag) {
}
