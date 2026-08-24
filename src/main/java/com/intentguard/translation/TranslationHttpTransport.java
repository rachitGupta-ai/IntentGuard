package com.intentguard.translation;

/**
 * Minimal seam over a concrete {@code Translation_Provider}'s encrypted network call.
 *
 * <p>Mirrors the {@code com.intentguard.llm.GeminiTextGenerator} pattern: the production
 * implementation performs an HTTPS request to the provider endpoint and parses the response, while
 * tests inject a fake so an adapter's timeout, never-throw, and fallback behavior can be exercised
 * without any network or API key.
 *
 * <p>Implementations may throw any {@link Exception}; the calling adapter catches it and maps it to
 * {@link java.util.Optional#empty()} so the {@link TranslationProvider} contract of never throwing
 * across the service boundary is preserved (Req 8.1).
 */
@FunctionalInterface
interface TranslationHttpTransport {

    /**
     * Sends the masked text to the provider over encrypted transport and returns the raw
     * translated masked text.
     *
     * @param maskedText the Source_Text with Technical_Tokens already replaced by sentinels
     * @param source     the source {@code Supported_Language} tag
     * @param target     the target {@code Supported_Language} tag
     * @return the translated masked text returned by the provider
     * @throws Exception on any transport, timeout, protocol, or parsing failure
     */
    String translate(String maskedText, LanguageTag source, LanguageTag target) throws Exception;
}
