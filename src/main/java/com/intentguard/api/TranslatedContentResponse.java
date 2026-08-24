package com.intentguard.api;

import com.intentguard.translation.TranslationResult;

/**
 * Response body for {@code POST /api/content/translate}: the text the Control_Tower should present
 * plus the classified translation outcome (Req 2.1).
 *
 * <p>{@code text} is always safe to present — it is the Translated_Text on a successful translation
 * (or cache reuse) and the original English/Source_Text on any fall-through path, honouring the
 * feature's <em>fail to English, never block the operator</em> rule. {@code translated} is
 * {@code true} only when {@code text} is a machine translation, and {@code outcome} names the exact
 * path taken (for example {@code TRANSLATED}, {@code ENGLISH_PASSTHROUGH}, {@code PROVIDER_TIMEOUT}).
 *
 * @param text       the text to present
 * @param translated {@code true} only when {@code text} is a machine translation
 * @param outcome    the {@code TranslationOutcome} name classifying the path
 */
public record TranslatedContentResponse(String text, boolean translated, String outcome) {

    /** Builds a response from a {@link TranslationResult}. */
    public static TranslatedContentResponse from(TranslationResult result) {
        return new TranslatedContentResponse(
                result.text(), result.translated(), result.outcome().name());
    }
}
