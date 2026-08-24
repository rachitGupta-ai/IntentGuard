package com.intentguard.translation;

import com.intentguard.llm.LlmProperties;

/**
 * Constructs the translation prompt sent to the Gemini Generate API by
 * {@link GeminiTranslationProvider} (Req 12.1–12.4).
 *
 * <p>The prompt is structured to produce clean, deterministic output: it names the source and
 * target languages, instructs the model to preserve sentinel placeholders verbatim, and demands
 * only the translated text without preamble or formatting. The masked text is embedded at the end
 * so the model treats everything before it as the system instruction.
 */
final class GeminiTranslationPrompt {

    private GeminiTranslationPrompt() {
        // utility class — not instantiable
    }

    /**
     * Builds the full translation prompt for the Gemini Generate API.
     *
     * @param maskedText the source text with Technical_Tokens replaced by sentinels
     * @param source     the source language tag (BCP-47)
     * @param target     the target language tag (BCP-47)
     * @return the complete prompt string ready for {@code models.generateContent}
     */
    static String build(String maskedText, LanguageTag source, LanguageTag target) {
        return "Translate the following text from " + source.value() + " to " + target.value() + ".\n"
                + "Preserve any placeholder tokens (e.g., \u27E6IG0\u27E7, \u27E6IG1\u27E7) exactly as they appear.\n"
                + "Return only the translated text. Do not add any preamble, explanation, or formatting.\n"
                + "\n"
                + "Text to translate:\n"
                + maskedText;
    }

    /**
     * Returns the Gemini model name configured for translation calls.
     *
     * @param properties the LLM configuration properties
     * @return the model identifier, for example {@code "gemini-2.5-flash"}
     */
    static String modelName(LlmProperties properties) {
        return properties.getModel();
    }
}
