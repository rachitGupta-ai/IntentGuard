// Feature: indian-language-translation, Property 6: Identical translations are reused without a new provider request
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
 * Feature: indian-language-translation, Property 6: Identical translations are reused without a new
 * provider request.
 *
 * <p>For any Source_Text and target Supported_Language, translating the same pair a second time
 * returns the previously produced Translated_Text and issues no additional Translation_Provider
 * request (Validates: Requirements 9.3).
 *
 * <p>This exercises the {@link DefaultTranslationService} cache-reuse short-circuit (see the design's
 * Components section): the first call for a {@code (Source_Text, target)} pair runs the full
 * mask &rarr; provider &rarr; restore flow, produces outcome {@link TranslationOutcome#TRANSLATED},
 * and stores the Translated_Text keyed by {@code (Source_Text, target)}; a second call for the same
 * pair must return that stored text with outcome {@link TranslationOutcome#CACHED} and must
 * <strong>not</strong> touch the provider again. To prove no additional provider request is issued,
 * the service is wired to a counting {@link PassthroughTranslationProvider} whose
 * {@link PassthroughTranslationProvider#invocationCount() invocation count} must remain exactly one
 * across both calls, with the runtime config's active Translation_Provider id matching the fake's id
 * so the first call is genuinely served by the provider.
 *
 * <p>The passthrough fake returns the masked text unchanged, preserving every sentinel inserted by
 * {@link TechnicalTokenProtector}; the restore therefore reproduces every Technical_Token
 * byte-for-byte and the first call always succeeds as {@code TRANSLATED} rather than falling through
 * a token-integrity path. Content is generated as plain prose optionally mixed with Technical_Tokens
 * to stress that the caching key is the exact {@code (Source_Text, target)} pair.
 */
class CachedTranslationReuseProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 6: Identical translations are reused without a new provider request
    @Property(tries = 200)
    void identicalTranslationsAreReusedWithoutANewProviderRequest(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("supportedNonEnglishTags") LanguageTag targetLang) {

        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(provider);

        // First translation of this (Source_Text, target) pair: the provider is consulted once and
        // produces a genuine Translated_Text with all Technical_Tokens preserved.
        TranslationResult first = service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang);
        assertThat(first.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(first.translated()).isTrue();
        assertThat(provider.invocationCount()).isEqualTo(1);

        // Second translation of the SAME pair: the prior Translated_Text is reused...
        TranslationResult second = service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang);
        assertThat(second.outcome()).isEqualTo(TranslationOutcome.CACHED);
        assertThat(second.translated()).isTrue();
        // ...the reused text equals the first result byte-for-byte...
        assertThat(second.text()).isEqualTo(first.text());
        // ...and NO additional Translation_Provider request was issued (still exactly one).
        assertThat(provider.invocationCount()).isEqualTo(1);
    }

    // ---- Worked example ---------------------------------------------------------------------------

    @Example
    void secondIdenticalRequestIsServedFromCache() {
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(provider);

        String source = "please review the alert for session-42 with score 0.91";
        LanguageTag target = LanguageTag.of("hi");

        TranslationResult first = service.translate(source, SupportedLanguages.ENGLISH, target);
        assertThat(first.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(provider.invocationCount()).isEqualTo(1);

        TranslationResult second = service.translate(source, SupportedLanguages.ENGLISH, target);
        assertThat(second.outcome()).isEqualTo(TranslationOutcome.CACHED);
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(provider.invocationCount()).isEqualTo(1);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} directly (no Spring) around the given counting fake,
     * seeding a {@link TranslationRuntimeConfig} whose active Translation_Provider id matches the
     * fake's id so the first request is genuinely served by the provider, with the default
     * Supported_Language set, a fresh cache, and the real {@link TechnicalTokenProtector}.
     */
    private DefaultTranslationService serviceWith(PassthroughTranslationProvider provider) {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, new SpeechProperties());
        runtimeConfig.initialize();
        return new DefaultTranslationService(
                List.of(provider),
                new TranslationCache(),
                runtimeConfig,
                supportedLanguages,
                new TechnicalTokenProtector());
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Non-empty content: plain prose optionally mixed with Technical_Tokens. The passthrough fake
        // preserves the masked sentinels, so the first call always succeeds as TRANSLATED.
        Arbitrary<String> tokens = Arbitraries.of(
                "/etc/passwd", "rm -rf /tmp/cache", "db.prod.internal", "0.87",
                "session-42", "2024-01-02T10:00:00Z", "DUAL_CONTROL_REQUIRED");
        Arbitrary<String> words = Arbitraries.of(
                "the", "operator", "should", "review", "this", "alert", "before", "approval");
        return Arbitraries.oneOf(words, words, tokens).list().ofMinSize(1).ofMaxSize(6)
                .map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<LanguageTag> supportedNonEnglishTags() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
