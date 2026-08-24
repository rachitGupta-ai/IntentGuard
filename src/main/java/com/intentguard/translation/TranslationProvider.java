package com.intentguard.translation;

import java.util.Optional;

/**
 * The pluggable external machine-translation dependency (Req 8.1).
 *
 * <p>This interface follows the established best-effort contract of
 * {@link com.intentguard.llm.LlmService}: implementations are external network calls that
 * <strong>never throw across the service boundary</strong>. Any timeout, transport error, provider
 * error, or malformed output is caught by the adapter and mapped to {@link Optional#empty()} so the
 * {@code TranslationService} can fall back deterministically to the original English content (Req
 * 2.4, 2.5, 8.7). Concrete adapters (for example {@code BhashiniTranslationProvider} and
 * {@code CloudTranslationProvider}) transmit only over encrypted transport (Req 11.1).
 *
 * <p>The provider operates on already-masked text: the {@code TranslationService} replaces every
 * Technical_Token with an opaque, translation-stable sentinel via
 * {@link TechnicalTokenProtector} before calling {@link #translate}, and restores the exact
 * original bytes afterward. A provider is therefore never expected to preserve token content
 * itself; it only needs to pass the sentinels through untouched.
 */
public interface TranslationProvider {

    /**
     * The stable identity of this provider, recorded on each Translation_Record (Req 8.7).
     *
     * @return a non-null, non-blank provider identifier, for example {@code "bhashini"}
     */
    String id();

    /**
     * Translates masked text from {@code source} into {@code target}, best-effort.
     *
     * <p>Implementations must never throw: on timeout, transport error, provider error, or
     * malformed output they return {@link Optional#empty()} so the caller can fall back to the
     * original English content.
     *
     * @param maskedText the Source_Text with Technical_Tokens already replaced by sentinels
     * @param source     the source {@code Supported_Language} tag
     * @param target     the target {@code Supported_Language} tag
     * @return the translated masked text, or {@link Optional#empty()} on timeout/error/failure
     */
    Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target);
}
