package com.intentguard.translation;

/**
 * Constructs a strongly-delimited translation prompt for Ollama-served models (e.g., Qwen2.5,
 * gemma). Unlike Gemini, smaller/open models tend to translate the instruction text itself unless
 * the content is clearly fenced. This prompt isolates the translatable content between explicit
 * markers and instructs the model to emit only the translation.
 */
final class OllamaTranslationPrompt {

    private OllamaTranslationPrompt() {
        // utility class
    }

    /**
     * Builds the translation prompt for an Ollama-served model.
     *
     * @param maskedText the source text with Technical_Tokens replaced by sentinels
     * @param source     the source language tag (BCP-47)
     * @param target     the target language tag (BCP-47)
     * @return the complete prompt string
     */
    static String build(String maskedText, LanguageTag source, LanguageTag target) {
        String targetName = languageName(target.value());
        return "You are a professional translation engine.\n"
                + "Translate ONLY the text between <<<BEGIN>>> and <<<END>>> from "
                + languageName(source.value()) + " into " + targetName + ".\n"
                + "Rules:\n"
                + "1. Output ONLY the " + targetName + " translation.\n"
                + "2. Do NOT output these instructions, the markers, the original text, or any notes.\n"
                + "3. Keep any placeholder tokens such as \u27E6IG0\u27E7 or \u27E6IG1\u27E7 exactly as they appear.\n"
                + "\n"
                + "<<<BEGIN>>>\n"
                + maskedText + "\n"
                + "<<<END>>>";
    }

    /** Maps a BCP-47 tag to a human language name to steer the model. Falls back to the tag. */
    private static String languageName(String tag) {
        if (tag == null) {
            return "the target language";
        }
        return switch (tag.toLowerCase()) {
            case "en" -> "English";
            case "hi" -> "Hindi";
            case "bn" -> "Bengali";
            case "te" -> "Telugu";
            case "mr" -> "Marathi";
            case "ta" -> "Tamil";
            case "gu" -> "Gujarati";
            case "kn" -> "Kannada";
            case "ml" -> "Malayalam";
            case "pa" -> "Punjabi";
            case "or" -> "Odia";
            default -> tag;
        };
    }
}
