package com.intentguard.translation;

/**
 * The IntentGuard-side orchestrator for all text translation (Req 2, 6.4, 9.3).
 *
 * <p>This is pure orchestration logic around a pluggable {@link TranslationProvider}, so it is unit-
 * and property-testable with an in-memory fake provider. A single {@link #translate} entry point
 * covers both outbound Operator_Facing_Content (English &rarr; Language_Preference) and, by
 * direction of the tags, other translation directions, applying — in order — English passthrough,
 * the unsupported-language guard, cache reuse, Technical_Token masking/restoring with an integrity
 * check, and a timeout-bounded provider call that falls back to English on timeout or error. The
 * classified {@link TranslationOutcome} on the returned {@link TranslationResult} records which path
 * produced the presented text.
 *
 * <p>The overarching contract is <strong>fail to English, never block the operator</strong>: no
 * input, provider, or token-integrity condition ever throws across this boundary; each degrades to
 * returning the original text with the appropriate outcome.
 */
public interface TranslationService {

    /**
     * Translates {@code sourceText} from {@code sourceLang} into {@code targetLang}, preserving every
     * Technical_Token byte-for-byte. Content is treated as non-sensitive; this is a convenience
     * overload that delegates to {@link #translate(String, LanguageTag, LanguageTag, boolean)} with
     * {@code sensitive == false}.
     *
     * @param sourceText the Source_Text to translate; {@code null} is treated as empty
     * @param sourceLang the source {@code Supported_Language} tag
     * @param targetLang the target {@code Supported_Language} tag
     * @return a {@link TranslationResult} whose {@link TranslationResult#text() text} is always safe
     *         to present and whose {@link TranslationResult#outcome() outcome} classifies the path
     */
    default TranslationResult translate(String sourceText, LanguageTag sourceLang, LanguageTag targetLang) {
        return translate(sourceText, sourceLang, targetLang, false);
    }

    /**
     * Translates {@code sourceText} from {@code sourceLang} into {@code targetLang}, preserving every
     * Technical_Token byte-for-byte, honoring the sensitive-content gate (Req 11.3).
     *
     * <p>When {@code sensitive} is {@code true} and the active runtime configuration does not permit
     * outbound translation of sensitive content
     * ({@link TranslationRuntimeConfig.Snapshot#sensitiveContentTranslatable()} is {@code false}), the
     * content is presented in English and is <strong>never transmitted to the Translation_Provider</strong>;
     * the returned {@link TranslationResult} carries the original content with outcome
     * {@link TranslationOutcome#ENGLISH_PASSTHROUGH}. Otherwise the full translation flow runs exactly
     * as for non-sensitive content.
     *
     * @param sourceText the Source_Text to translate; {@code null} is treated as empty
     * @param sourceLang the source {@code Supported_Language} tag
     * @param targetLang the target {@code Supported_Language} tag
     * @param sensitive  whether the content is marked sensitive; when {@code true} and configuration
     *                   forbids it, the content is never sent to the provider
     * @return a {@link TranslationResult} whose {@link TranslationResult#text() text} is always safe
     *         to present and whose {@link TranslationResult#outcome() outcome} classifies the path
     */
    TranslationResult translate(String sourceText, LanguageTag sourceLang, LanguageTag targetLang,
            boolean sensitive);

    /**
     * Translates an inbound Declared_Intent from {@code sourceLang} into the Engine_Language
     * (English) so the Enforcement_Engine can open the Intent_Session on the English text
     * (Req 3.1, 3.2).
     *
     * <p>Unlike {@link #translate}, this path is <strong>not</strong> short-circuited when the
     * target is English — the target is <em>always</em> English here and the point of the call is to
     * produce the Engine_Language text. It still preserves every Technical_Token byte-for-byte,
     * bounds the provider call by the configured timeout, and classifies the outcome so the caller
     * can distinguish a usable translation from a failure:
     * <ul>
     *   <li>a non-English Supported_Language source runs the full mask &rarr; provider &rarr; restore
     *       &rarr; integrity flow, yielding {@link TranslationOutcome#TRANSLATED} (or
     *       {@link TranslationOutcome#CACHED});</li>
     *   <li>an English source is returned unchanged as {@link TranslationOutcome#ENGLISH_PASSTHROUGH}
     *       (there is nothing to translate);</li>
     *   <li>a source tag outside the Supported_Language set yields
     *       {@link TranslationOutcome#UNSUPPORTED_LANGUAGE};</li>
     *   <li>a provider timeout/error yields {@link TranslationOutcome#PROVIDER_TIMEOUT} /
     *       {@link TranslationOutcome#PROVIDER_ERROR}, and a lost Technical_Token yields
     *       {@link TranslationOutcome#TOKEN_INTEGRITY_FALLBACK} — in each case the returned
     *       {@link TranslationResult#text()} is the original Source_Text, which is <em>not</em>
     *       usable as Engine_Language text.</li>
     * </ul>
     *
     * @param sourceText the Declared_Intent Source_Text; {@code null} is treated as empty
     * @param sourceLang the source {@code Supported_Language} tag the intent was submitted in
     * @return a {@link TranslationResult} whose {@link TranslationResult#outcome() outcome} tells the
     *         caller whether {@link TranslationResult#text() text} is usable Engine_Language text
     */
    TranslationResult translateInbound(String sourceText, LanguageTag sourceLang);
}
