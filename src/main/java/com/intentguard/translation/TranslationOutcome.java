package com.intentguard.translation;

/**
 * The classified outcome of a {@link TranslationService#translate} call (see {@link TranslationResult}).
 *
 * <p>Every outcome except {@link #TRANSLATED} and {@link #CACHED} corresponds to a
 * <em>fall-through</em> path in which the {@code Translation_Service} returns text unchanged rather
 * than a machine translation — the overarching rule of the feature is <strong>fail to English,
 * never block the operator</strong>. The enum lets callers (and property tests) assert on the exact
 * path taken without inspecting provider internals.
 *
 * <ul>
 *   <li>{@link #TRANSLATED} - the provider produced a translation whose Technical_Tokens were all
 *       preserved; the Translated_Text is returned.</li>
 *   <li>{@link #ENGLISH_PASSTHROUGH} - the target language is English, so the content is returned
 *       unchanged and no Translation_Provider request is issued (Req 2.2).</li>
 *   <li>{@link #CACHED} - an identical {@code (Source_Text, target)} pair was already translated;
 *       the prior Translated_Text is reused without a new provider request (Req 9.3).</li>
 *   <li>{@link #UNSUPPORTED_LANGUAGE} - either language tag is outside the configured
 *       Supported_Language set; the original English content is returned (Req 6.4).</li>
 *   <li>{@link #PROVIDER_TIMEOUT} - the provider did not respond within the configured translation
 *       timeout; the original English content is returned (Req 2.4, 9.1).</li>
 *   <li>{@link #PROVIDER_ERROR} - the provider returned an error / no result; the original English
 *       content is returned (Req 2.5).</li>
 *   <li>{@link #TOKEN_INTEGRITY_FALLBACK} - the provider's output could not reproduce every
 *       Technical_Token, so the original Source_Text is returned and <strong>no</strong> translation
 *       failure is recorded (Req 7.4).</li>
 * </ul>
 */
public enum TranslationOutcome {
    TRANSLATED,
    ENGLISH_PASSTHROUGH,
    CACHED,
    UNSUPPORTED_LANGUAGE,
    PROVIDER_TIMEOUT,
    PROVIDER_ERROR,
    TOKEN_INTEGRITY_FALLBACK
}
