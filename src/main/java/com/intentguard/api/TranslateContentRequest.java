package com.intentguard.api;

/**
 * Request body for {@code POST /api/content/translate}: on-demand outbound translation of an item
 * of Operator_Facing_Content into an Operator's Language_Preference (Req 2.1).
 *
 * <p>{@code content} is the English Source_Text to translate; {@code targetLanguageTag} is the
 * BCP-47 tag of the language to translate into (for example {@code "hi"}). {@code sourceLanguageTag}
 * is optional and defaults to English ({@code "en"}) when {@code null}/blank, matching the common
 * outbound case (English &rarr; Language_Preference). {@code sensitive} marks the content as
 * sensitive so the Translation_Service honours the sensitive-content gate (Req 11.3): when
 * {@code true} and configuration forbids it, the content is presented in English and never sent to
 * the provider.
 *
 * @param content           the Source_Text to translate
 * @param targetLanguageTag the BCP-47 tag to translate into
 * @param sourceLanguageTag the BCP-47 source tag, or {@code null}/blank for English
 * @param sensitive         whether the content is marked sensitive
 */
public record TranslateContentRequest(
        String content, String targetLanguageTag, String sourceLanguageTag, boolean sensitive) {
}
