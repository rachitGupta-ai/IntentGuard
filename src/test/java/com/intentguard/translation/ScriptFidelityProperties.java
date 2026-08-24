// Feature: indian-language-translation, Property 16: Script fidelity is preserved end-to-end
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
import net.jqwik.api.Tuple;

/**
 * Feature: indian-language-translation, Property 16: Script fidelity is preserved end-to-end.
 *
 * <p>For any Translated_Text returned by the provider — including native Indian-script (non-ASCII)
 * content — the string the Control_Tower presents equals the provider's output code point for code
 * point, with no lossy re-encoding (Validates: Requirements 6.3).
 *
 * <p>This exercises the real {@link DefaultTranslationService} against a
 * {@link NativeScriptTranslationProvider} that returns native-script output across the Devanagari,
 * Bengali, Tamil, Telugu, Kannada, Malayalam, Gujarati, Gurmukhi, and Odia code point ranges. To
 * isolate script fidelity from Technical_Token restoration, the Source_Text is token-free English
 * prose: masking finds no tokens, so restore is the identity and the provider's native-script output
 * is presented verbatim. The service is wired with its active Translation_Provider id matching the
 * fake's id so the translation path is fully live and reaches {@link TranslationOutcome#TRANSLATED}.
 */
class ScriptFidelityProperties {

    /** A native Indian-script sample paired with the supported target language it renders in. */
    record NativeScriptSample(String nativeOutput, LanguageTag target, String scriptName) {}

    /** An Indic script block (BMP letter sub-range) paired with a matching Supported_Language. */
    private record Script(String name, int start, int end, String target) {}

    // Letter sub-ranges chosen inside each Indic Unicode block; every code point is in the BMP.
    private static final List<Script> SCRIPTS = List.of(
            new Script("Devanagari", 0x0905, 0x0939, "hi"),
            new Script("Bengali", 0x0985, 0x09B9, "bn"),
            new Script("Tamil", 0x0B85, 0x0BB9, "ta"),
            new Script("Telugu", 0x0C05, 0x0C39, "te"),
            new Script("Kannada", 0x0C85, 0x0CB9, "kn"),
            new Script("Malayalam", 0x0D05, 0x0D39, "ml"),
            new Script("Gujarati", 0x0A85, 0x0AB9, "gu"),
            new Script("Gurmukhi", 0x0A05, 0x0A39, "pa"),
            new Script("Odia", 0x0B05, 0x0B39, "or"));

    // Feature: indian-language-translation, Property 16: Script fidelity is preserved end-to-end
    @Property(tries = 200)
    void nativeScriptOutputIsPresentedCodePointForCodePoint(
            @ForAll("tokenFreeSource") String source,
            @ForAll("nativeScriptSamples") NativeScriptSample sample) {

        NativeScriptTranslationProvider provider =
                new NativeScriptTranslationProvider(sample.nativeOutput());
        DefaultTranslationService service = newService(provider);

        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, sample.target());

        // The translation path actually ran (not a fallback) and the provider was invoked once.
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.translated()).isTrue();
        assertThat(provider.invocationCount()).isEqualTo(1);

        // Script fidelity: the presented string equals the provider output byte-for-byte...
        assertThat(result.text()).isEqualTo(sample.nativeOutput());
        // ...and code point for code point, with no lossy re-encoding.
        assertThat(result.text().codePoints().toArray())
                .isEqualTo(sample.nativeOutput().codePoints().toArray());
    }

    @Example
    void devanagariOutputIsPreservedExactly() {
        // "नमस्ते संदेश" — Devanagari greeting; must be presented code point for code point.
        String nativeOutput = "\u0928\u092e\u0938\u094d\u0924\u0947 \u0938\u0902\u0926\u0947\u0936";
        NativeScriptTranslationProvider provider = new NativeScriptTranslationProvider(nativeOutput);
        DefaultTranslationService service = newService(provider);

        TranslationResult result =
                service.translate("please review this alert", SupportedLanguages.ENGLISH, LanguageTag.of("hi"));

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.text()).isEqualTo(nativeOutput);
        assertThat(result.text().codePoints().toArray())
                .isEqualTo(nativeOutput.codePoints().toArray());
    }

    // --- Service wiring --------------------------------------------------------------------------

    /**
     * Constructs a live {@link DefaultTranslationService} around the given native-script fake,
     * seeding a {@link TranslationRuntimeConfig} whose active Translation_Provider id matches the
     * fake's id so the fake is selected and invoked on the translation path.
     */
    private static DefaultTranslationService newService(NativeScriptTranslationProvider provider) {
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
    Arbitrary<String> tokenFreeSource() {
        // Plain English prose with no Technical_Tokens (no digits, paths, hosts, dotted ids, reason
        // codes, or known executables), so masking finds nothing and restore is the identity — the
        // provider's native-script output is therefore presented verbatim.
        Arbitrary<String> words = Arbitraries.of(
                "please", "review", "the", "alert", "and", "confirm", "operator",
                "should", "read", "this", "message", "carefully", "before", "approval");
        return words.list().ofMinSize(1).ofMaxSize(8).map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<NativeScriptSample> nativeScriptSamples() {
        return Arbitraries.of(SCRIPTS).flatMap(script -> {
            Arbitrary<Integer> codePoints = Arbitraries.integers().between(script.start(), script.end());
            Arbitrary<Integer> spaceOrLetter = Arbitraries.frequencyOf(
                    Tuple.of(6, codePoints),
                    Tuple.of(1, Arbitraries.just((int) ' ')));
            return spaceOrLetter.list().ofMinSize(1).ofMaxSize(24).map(cps -> {
                StringBuilder sb = new StringBuilder();
                for (int cp : cps) {
                    sb.appendCodePoint(cp);
                }
                return new NativeScriptSample(sb.toString(), LanguageTag.of(script.target()), script.name());
            });
        });
    }
}
