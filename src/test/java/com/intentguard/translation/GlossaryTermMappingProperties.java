// Feature: indian-language-translation, Property 18: Glossary terms map to their configured translations
package com.intentguard.translation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.speech.SpeechProperties;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 18: Glossary terms map to their configured
 * translations (Stretch).
 *
 * <p>For any content containing configured domain-glossary terms, each glossary term is rendered as
 * its configured Translated_Text (Validates: Requirements 12.2).
 *
 * <p>This exercises the {@link DomainGlossary} mask/restore seam wired into
 * {@link DefaultTranslationService}: for the target language a glossary is configured for, every
 * configured term is replaced with an opaque {@code ⟦GLOSS#⟧} sentinel before the text reaches the
 * {@link TranslationProvider}, and restored to the exact configured {@code Translated_Text} on the
 * way back. Because the {@link PassthroughTranslationProvider} returns the masked text unchanged, it
 * preserves both the Technical_Token sentinels and the glossary sentinels, so the configured
 * translation is restored deterministically and the outcome is {@link TranslationOutcome#TRANSLATED}.
 *
 * <p>The property additionally asserts that a term configured for one target language does not leak
 * into a different target language: translating the same content to a language the glossary is
 * <em>not</em> configured for never renders the first language's configured translation, since the
 * glossary is keyed by target {@link LanguageTag}.
 *
 * <p>Glossary terms are plain lower-case security phrases (for example {@code "blast radius"},
 * {@code "dual control"}) chosen so they are not themselves detected as Technical_Tokens; their
 * configured translations are native Devanagari (Hindi) strings, so their presence in the result is
 * unambiguous and cannot collide with the English prose or terms.
 */
class GlossaryTermMappingProperties {

    private static final LanguageTag HINDI = LanguageTag.of("hi");
    private static final LanguageTag BENGALI = LanguageTag.of("bn");

    /**
     * The domain glossary configured for Hindi: security terminology term &rarr; its
     * operator-approved Devanagari {@code Translated_Text}. Ordered so the fixture is stable.
     */
    private static final Map<String, String> HINDI_GLOSSARY = new LinkedHashMap<>();

    static {
        HINDI_GLOSSARY.put("blast radius", "\u092a\u094d\u0930\u092d\u093e\u0935 \u0915\u094d\u0937\u0947\u0924\u094d\u0930"); // प्रभाव क्षेत्र
        HINDI_GLOSSARY.put("dual control", "\u0926\u094b\u0939\u0930\u093e \u0928\u093f\u092f\u0902\u0924\u094d\u0930\u0923"); // दोहरा नियंत्रण
        HINDI_GLOSSARY.put("privilege escalation", "\u0935\u093f\u0936\u0947\u0937\u093e\u0927\u093f\u0915\u093e\u0930 \u0935\u0943\u0926\u094d\u0927\u093f"); // विशेषाधिकार वृद्धि
        HINDI_GLOSSARY.put("rollback", "\u0935\u093e\u092a\u0938 \u0932\u0947\u0928\u093e"); // वापस लेना
        HINDI_GLOSSARY.put("audit trail", "\u0932\u0947\u0916\u093e \u092a\u0930\u0940\u0915\u094d\u0937\u093e \u092a\u0925"); // लेखा परीक्षा पथ
    }

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 18: Glossary terms map to their configured translations
    @Property(tries = 200)
    void glossaryTermsMapToTheirConfiguredTranslations(
            @ForAll("embeddedTerms") Set<String> termsToEmbed,
            @ForAll("prose") List<String> prose) {

        String content = weave(prose, termsToEmbed);

        DomainGlossary glossary = DomainGlossary.forLanguage(HINDI, HINDI_GLOSSARY);
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(provider, glossary);

        // Translate into the language the glossary IS configured for.
        TranslationResult hindi = service.translate(content, SupportedLanguages.ENGLISH, HINDI);

        // The full mask -> passthrough -> restore flow succeeds: every Technical_Token (there are
        // none here) and every glossary sentinel is preserved, so the outcome is a genuine
        // translation rather than a fallback.
        assertThat(hindi.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(hindi.translated()).isTrue();

        // Req 12.2: each configured glossary term embedded in the content is rendered as its
        // configured Translated_Text.
        for (String term : termsToEmbed) {
            String configuredTranslation = HINDI_GLOSSARY.get(term);
            assertThat(hindi.text())
                    .as("configured Translated_Text for glossary term '%s'", term)
                    .contains(configuredTranslation);
        }

        // Translate the SAME content into a language the glossary is NOT configured for: the
        // Hindi-configured translations must never leak across the target-language boundary.
        TranslationResult bengali = service.translate(content, SupportedLanguages.ENGLISH, BENGALI);
        for (String term : termsToEmbed) {
            String hindiTranslation = HINDI_GLOSSARY.get(term);
            assertThat(bengali.text())
                    .as("Hindi glossary translation for '%s' must not leak into Bengali", term)
                    .doesNotContain(hindiTranslation);
        }
    }

    // ---- Worked example ---------------------------------------------------------------------------

    @Example
    void configuredTermsAreRenderedInHindiWhileTokensArePreserved() {
        DomainGlossary glossary = DomainGlossary.forLanguage(HINDI, HINDI_GLOSSARY);
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        DefaultTranslationService service = serviceWith(provider, glossary);

        // Prose mixing two configured terms with a Technical_Token (a path) that must survive intact.
        String content = "review the blast radius before dual control approves /etc/passwd";

        TranslationResult result = service.translate(content, SupportedLanguages.ENGLISH, HINDI);

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        // Both configured terms render as their configured Hindi Translated_Text...
        assertThat(result.text()).contains(HINDI_GLOSSARY.get("blast radius"));
        assertThat(result.text()).contains(HINDI_GLOSSARY.get("dual control"));
        // ...the Technical_Token is preserved byte-for-byte...
        assertThat(result.text()).contains("/etc/passwd");
        // ...and the English glossary terms no longer appear (they were mapped).
        assertThat(result.text()).doesNotContain("blast radius");
        assertThat(result.text()).doesNotContain("dual control");

        // A different target language the glossary is not configured for gets no Hindi mapping.
        TranslationResult bengali = service.translate(content, SupportedLanguages.ENGLISH, BENGALI);
        assertThat(bengali.text()).doesNotContain(HINDI_GLOSSARY.get("blast radius"));
        assertThat(bengali.text()).doesNotContain(HINDI_GLOSSARY.get("dual control"));
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} directly (no Spring) around the given passthrough
     * fake and glossary via the 6-arg test constructor, seeding a {@link TranslationRuntimeConfig}
     * whose active Translation_Provider id matches the fake's id (so a translation is genuinely
     * attempted) with the default Supported_Language set, a fresh cache, and the real
     * {@link TechnicalTokenProtector}.
     */
    private DefaultTranslationService serviceWith(
            PassthroughTranslationProvider provider, DomainGlossary glossary) {
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
                new TechnicalTokenProtector(),
                glossary);
    }

    /**
     * Weaves the generated prose and the terms-to-embed into a single Source_Text, ensuring every
     * term appears surrounded by prose and separated by whitespace so it is matched intact by the
     * glossary. Each term is guaranteed to be present at least once.
     */
    private static String weave(List<String> prose, Set<String> terms) {
        List<String> parts = new ArrayList<>();
        List<String> proseList = new ArrayList<>(prose);
        int p = 0;
        parts.add(proseList.isEmpty() ? "the" : proseList.get(p++ % proseList.size()));
        for (String term : terms) {
            parts.add(term);
            parts.add(proseList.isEmpty() ? "and" : proseList.get(p++ % proseList.size()));
        }
        return String.join(" ", parts);
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<Set<String>> embeddedTerms() {
        // A non-empty subset of the configured glossary terms.
        return Arbitraries.of(new ArrayList<>(HINDI_GLOSSARY.keySet()))
                .set().ofMinSize(1).ofMaxSize(HINDI_GLOSSARY.size());
    }

    @Provide
    Arbitrary<List<String>> prose() {
        // Plain English prose words that are neither glossary terms nor Technical_Tokens, so they
        // never interfere with term matching or token detection.
        return Arbitraries.of(
                "the", "operator", "should", "review", "this", "alert", "before",
                "approval", "and", "then", "please", "carefully", "the", "session")
                .list().ofMinSize(1).ofMaxSize(8);
    }
}
