// Feature: indian-language-translation, Property 5: Unsupported languages fall back to English and are recorded
package com.intentguard.translation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.speech.SpeechProperties;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 5: Unsupported languages fall back to English and
 * are recorded.
 *
 * <p>For any language tag outside the configured Supported_Language set — including when the
 * Translation_Provider is unavailable — the {@link TranslationService} returns the original English
 * content unchanged and records the language as {@link TranslationOutcome#UNSUPPORTED_LANGUAGE}
 * (Validates: Requirements 6.4).
 *
 * <p>The design places the unsupported-language guard <em>before</em> provider resolution (see
 * {@link DefaultTranslationService#translate}), so the outcome is {@code UNSUPPORTED_LANGUAGE} even
 * when no provider is registered for the active identity. This test exercises the real
 * {@link DefaultTranslationService} against the {@link SupportedLanguages#defaults() default
 * Supported_Language set}, driving unsupported tags into either the source or the target position
 * and toggling whether a provider is registered at all. In every case it asserts the presented text
 * is the original Source_Text byte-for-byte, the result is not marked translated, and — when a spy
 * provider is present — that it was never invoked (the guard short-circuits first).
 */
class UnsupportedLanguageFallbackProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    /** Where the unsupported tag sits: as the source language or the target language. */
    enum Placement {
        /** Unsupported source, supported non-English target. */
        SOURCE_UNSUPPORTED,
        /** Supported source (English), unsupported target. */
        TARGET_UNSUPPORTED
    }

    // Feature: indian-language-translation, Property 5: Unsupported languages fall back to English and are recorded
    @Property(tries = 200)
    void unsupportedLanguagesFallBackToEnglishAndAreRecorded(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("unsupportedTags") LanguageTag unsupportedTag,
            @ForAll("supportedNonEnglishTags") LanguageTag supportedTarget,
            @ForAll Placement placement,
            @ForAll boolean providerAvailable) {

        LanguageTag sourceLang;
        LanguageTag targetLang;
        if (placement == Placement.SOURCE_UNSUPPORTED) {
            // Unsupported source; target is a supported non-English language so the English
            // passthrough guard does not short-circuit ahead of the unsupported-language guard.
            sourceLang = unsupportedTag;
            targetLang = supportedTarget;
        } else {
            // Supported English source; target is unsupported (and therefore non-English).
            sourceLang = SupportedLanguages.ENGLISH;
            targetLang = unsupportedTag;
        }

        PassthroughTranslationProvider spy = new PassthroughTranslationProvider();
        // When the provider is "unavailable" no provider is registered for the active identity;
        // the guard runs before provider resolution so the outcome is unchanged either way.
        List<TranslationProvider> providers = providerAvailable ? List.of(spy) : List.of();
        String activeProviderId = providerAvailable ? spy.id() : "no-such-provider";
        DefaultTranslationService service = serviceWith(providers, activeProviderId);

        TranslationResult result = service.translate(sourceText, sourceLang, targetLang);

        // The language is recorded as unsupported (Req 6.4)...
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.UNSUPPORTED_LANGUAGE);
        // ...the original English content is returned byte-for-byte unchanged...
        assertThat(result.text()).isEqualTo(sourceText);
        // ...and it is not presented as a machine translation.
        assertThat(result.translated()).isFalse();
        // The guard short-circuits before any provider request is issued.
        assertThat(spy.invocationCount()).isZero();
    }

    // ---- Worked examples --------------------------------------------------------------------------

    @Example
    void unsupportedTargetWithProviderAvailableFallsBackToEnglish() {
        PassthroughTranslationProvider spy = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(List.of(spy), spy.id());

        String source = "please review the alert for session-42";
        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, LanguageTag.of("fr"));

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.UNSUPPORTED_LANGUAGE);
        assertThat(result.text()).isEqualTo(source);
        assertThat(result.translated()).isFalse();
        assertThat(spy.invocationCount()).isZero();
    }

    @Example
    void unsupportedTargetWithProviderUnavailableStillReportsUnsupported() {
        // No provider is registered at all: the unsupported-language guard runs before provider
        // resolution, so UNSUPPORTED_LANGUAGE is still reported rather than a PROVIDER_ERROR.
        DefaultTranslationService service = serviceWith(List.of(), "no-such-provider");

        String source = "run backup on host db.prod.internal";
        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, LanguageTag.of("zz"));

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.UNSUPPORTED_LANGUAGE);
        assertThat(result.text()).isEqualTo(source);
        assertThat(result.translated()).isFalse();
    }

    @Example
    void unsupportedSourceWithSupportedTargetFallsBackToEnglish() {
        PassthroughTranslationProvider spy = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(List.of(spy), spy.id());

        String source = "recognized intent text";
        TranslationResult result = service.translate(source, LanguageTag.of("xx"), LanguageTag.of("hi"));

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.UNSUPPORTED_LANGUAGE);
        assertThat(result.text()).isEqualTo(source);
        assertThat(result.translated()).isFalse();
        assertThat(spy.invocationCount()).isZero();
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} directly (no Spring) around the given providers and
     * an active Translation_Provider identity, using the default Supported_Language set, a fresh
     * cache, and the real {@link TechnicalTokenProtector}.
     */
    private DefaultTranslationService serviceWith(List<TranslationProvider> providers, String activeProviderId) {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(activeProviderId);
        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, new SpeechProperties());
        runtimeConfig.initialize();
        return new DefaultTranslationService(
                providers,
                new TranslationCache(),
                runtimeConfig,
                supportedLanguages,
                new TechnicalTokenProtector());
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        Arbitrary<String> tokens = Arbitraries.of(
                "/etc/passwd", "rm -rf /tmp/cache", "db.prod.internal", "0.87",
                "session-42", "2024-01-02T10:00:00Z", "DUAL_CONTROL_REQUIRED");
        Arbitrary<String> words = Arbitraries.of(
                "the", "operator", "should", "review", "this", "alert", "before", "approval");
        return Arbitraries.oneOf(tokens, words).list().ofMinSize(1).ofMaxSize(6)
                .map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<LanguageTag> unsupportedTags() {
        // A mix of well-known non-Indian tags and random alphabetic tags, all filtered to be outside
        // the configured Supported_Language set (after LanguageTag's lower-casing normalization).
        Arbitrary<String> known = Arbitraries.of("fr", "de", "es", "ja", "ru", "zh", "xx", "zz", "qq");
        Arbitrary<String> random = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(2).ofMaxLength(8);
        return Arbitraries.oneOf(known, random)
                .map(LanguageTag::of)
                .filter(tag -> !supportedLanguages.isSupported(tag));
    }

    @Provide
    Arbitrary<LanguageTag> supportedNonEnglishTags() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
