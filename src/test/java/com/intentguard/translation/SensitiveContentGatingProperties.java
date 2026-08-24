// Feature: indian-language-translation, Property 15: Non-translatable sensitive content is never sent to the provider
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
 * Feature: indian-language-translation, Property 15: Non-translatable sensitive content is never
 * sent to the provider.
 *
 * <p>For any content marked sensitive when outbound translation of sensitive content is not
 * permitted by configuration, the Control_Tower presents that content in English and never
 * transmits it to the Translation_Provider (Validates: Requirements 11.3).
 *
 * <p>This exercises the {@link DefaultTranslationService} sensitive-content gate: when a request is
 * marked {@code sensitive} and the active
 * {@link TranslationRuntimeConfig.Snapshot#sensitiveContentTranslatable()} flag is {@code false},
 * the service must return the original English content byte-for-byte with outcome
 * {@link TranslationOutcome#ENGLISH_PASSTHROUGH} and {@code translated == false}, without ever
 * touching the configured provider. To prove the content never leaves the process, the service is
 * wired to a counting {@link PassthroughTranslationProvider} whose
 * {@link PassthroughTranslationProvider#invocationCount() invocation count} must remain exactly
 * zero, with the runtime config's active Translation_Provider id matching the fake's id so the
 * wiring is otherwise fully live (the fake would be selected and invoked absent the gate).
 *
 * <p>A contrasting case ({@link #sensitiveContentIsTranslatedWhenPermitted()}) flips the
 * configuration to {@code sensitiveContentTranslatable == true} and asserts the same sensitive
 * content IS translated (the provider is invoked and the outcome is {@link TranslationOutcome#TRANSLATED}),
 * proving the gate is specifically the not-permitted case rather than a blanket refusal.
 */
class SensitiveContentGatingProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 15: Non-translatable sensitive content is never sent to the provider
    @Property(tries = 200)
    void nonTranslatableSensitiveContentIsNeverSentToTheProvider(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("supportedNonEnglishTags") LanguageTag targetLang) {

        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        // Configuration does NOT permit translating sensitive content (Req 11.3).
        DefaultTranslationService service = serviceWith(provider, false);

        TranslationResult result =
                service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang, true);

        // The content is presented in English, byte-for-byte unchanged...
        assertThat(result.text()).isEqualTo(sourceText);
        assertThat(result.translated()).isFalse();
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.ENGLISH_PASSTHROUGH);
        // ...and it was NEVER transmitted to the Translation_Provider.
        assertThat(provider.invocationCount()).isZero();
    }

    // ---- Worked example: sensitive content is gated -----------------------------------------------

    @Example
    void sensitiveContentWithTokensStaysEnglishAndUnsent() {
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(provider, false);

        String source = "rotate the credential for db.prod.internal before 2024-01-15T02:30:00Z";
        LanguageTag target = LanguageTag.of("hi");

        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, target, true);

        assertThat(result.text()).isEqualTo(source);
        assertThat(result.translated()).isFalse();
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.ENGLISH_PASSTHROUGH);
        assertThat(provider.invocationCount()).isZero();
    }

    // ---- Contrasting case: the gate is specifically the not-permitted case ------------------------

    @Property(tries = 200)
    void sensitiveContentIsTranslatedWhenPermitted(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("supportedNonEnglishTags") LanguageTag targetLang) {

        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        // Configuration DOES permit translating sensitive content: the gate must not apply.
        DefaultTranslationService service = serviceWith(provider, true);

        TranslationResult result =
                service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang, true);

        // The very same sensitive content is now genuinely translated...
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.translated()).isTrue();
        // ...proving the provider WAS invoked (the content was transmitted) when permitted.
        assertThat(provider.invocationCount()).isEqualTo(1);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} directly (no Spring) around the given counting fake,
     * seeding a {@link TranslationRuntimeConfig} whose active Translation_Provider id matches the
     * fake's id (so a permitted request is genuinely served by the provider) and whose
     * {@code sensitiveContentTranslatable} flag is set as requested, with the default
     * Supported_Language set, a fresh cache, and the real {@link TechnicalTokenProtector}.
     */
    private DefaultTranslationService serviceWith(PassthroughTranslationProvider provider,
            boolean sensitiveContentTranslatable) {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        translationProperties.setSensitiveContentTranslatable(sensitiveContentTranslatable);
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
        // preserves the masked sentinels, so a permitted translation always succeeds as TRANSLATED.
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
