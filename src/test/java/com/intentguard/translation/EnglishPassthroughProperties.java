// Feature: indian-language-translation, Property 3: English preference is an identity passthrough
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
 * Feature: indian-language-translation, Property 3: English preference is an identity passthrough.
 *
 * <p>For any Operator_Facing_Content, translating with target English returns the content unchanged
 * and issues no Translation_Provider request (Validates: Requirements 2.2).
 *
 * <p>This exercises the {@link DefaultTranslationService} English-passthrough short-circuit: when
 * the target language is the Engine_Language ({@link SupportedLanguages#ENGLISH}) the service must
 * return the input byte-for-byte with outcome {@link TranslationOutcome#ENGLISH_PASSTHROUGH} and
 * {@code translated == false}, without ever touching the configured provider. To prove the provider
 * is never invoked, the service is wired to a counting {@link PassthroughTranslationProvider} whose
 * {@link PassthroughTranslationProvider#invocationCount() invocation count} must remain zero, with
 * the runtime config's active Translation_Provider id matching the fake's id so the wiring is
 * otherwise fully live.
 */
class EnglishPassthroughProperties {

    // Feature: indian-language-translation, Property 3: English preference is an identity passthrough
    @Property(tries = 200)
    void englishTargetIsIdentityPassthroughWithNoProviderRequest(
            @ForAll("operatorFacingContent") String content,
            @ForAll("anySourceLanguage") LanguageTag source) {

        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = newService(provider);

        TranslationResult result = service.translate(content, source, SupportedLanguages.ENGLISH);

        // Identity passthrough: the content is returned byte-for-byte unchanged...
        assertThat(result.text()).isEqualTo(content);
        assertThat(result.translated()).isFalse();
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.ENGLISH_PASSTHROUGH);
        // ...and no Translation_Provider request was ever issued.
        assertThat(provider.invocationCount()).isZero();
    }

    @Example
    void nativeScriptContentWithTokensPassesThroughUntouched() {
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = newService(provider);

        // Content already in a native Indian script that also carries Technical_Tokens; targeting
        // English must return it verbatim without a provider call.
        String content = "\u0905\u0932\u0930\u094d\u091f rm -rf /tmp/cache \u0938\u094d\u0915\u094b\u0930 0.91";

        TranslationResult result = service.translate(content, LanguageTag.of("hi"), SupportedLanguages.ENGLISH);

        assertThat(result.text()).isEqualTo(content);
        assertThat(result.translated()).isFalse();
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.ENGLISH_PASSTHROUGH);
        assertThat(provider.invocationCount()).isZero();
    }

    // --- Service wiring --------------------------------------------------------------------------

    /**
     * Constructs a live {@link DefaultTranslationService} around the given counting fake, seeding a
     * {@link TranslationRuntimeConfig} whose active Translation_Provider id matches the fake's id so
     * that, absent the English short-circuit, the fake would in fact be selected and invoked.
     */
    private static DefaultTranslationService newService(PassthroughTranslationProvider provider) {
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        SpeechProperties speechProperties = new SpeechProperties();

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, speechProperties);
        runtimeConfig.initialize();

        return new DefaultTranslationService(
                List.of(provider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> operatorFacingContent() {
        // Plain prose, mixed prose + Technical_Tokens, native-script (non-ASCII UTF-8) content, and
        // the empty string — all of which must pass through unchanged when targeting English.
        Arbitrary<String> prose = Arbitraries.strings().ascii().ofMaxLength(80);
        Arbitrary<String> mixed = Arbitraries.of(
                "run git status on host db.prod.internal",
                "score 0.91 at 2024-01-15T02:30:00Z code DUAL_CONTROL_REQUIRED",
                "please review /etc/passwd for session-42",
                "");
        Arbitrary<String> nativeScript = Arbitraries.of(
                "\u0939\u093f\u0928\u094d\u0926\u0940 \u0938\u0902\u0926\u0947\u0936",   // Hindi
                "\u09ac\u09be\u0982\u09b2\u09be \u09ac\u09be\u09b0\u09cd\u09a4\u09be",       // Bengali
                "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd \u0b8e\u0b9a\u0bcd\u0b9a\u0bb0\u0bbf\u0b95\u0bcd\u0b95\u0bc8"); // Tamil
        return Arbitraries.oneOf(prose, mixed, nativeScript);
    }

    @Provide
    Arbitrary<LanguageTag> anySourceLanguage() {
        // The source may be English or any supported Indian language; the English target alone
        // determines the passthrough, independent of the source.
        return Arbitraries.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
