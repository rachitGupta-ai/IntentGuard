package com.intentguard.translation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configurable domain glossary mapping security-terminology terms to their operator-approved
 * {@code Translated_Text} per target {@code Supported_Language} (Req 12.2, stretch scope).
 *
 * <p>Where a glossary is configured, the {@code TranslationService} renders each configured glossary
 * term as its configured translation rather than whatever the {@link TranslationProvider} would
 * produce, so security vocabulary (for example {@code "blast radius"} or {@code "dual control"})
 * stays consistent across languages. This is <strong>Property 18</strong> in the design.
 *
 * <p>Enforcement mirrors the {@link TechnicalTokenProtector} mask/restore technique so it is robust
 * against any provider behaviour: {@link #mask(String, LanguageTag)} replaces every configured term
 * with an opaque, translation-stable sentinel (for example {@code ⟦GLOSS0⟧}) that the provider
 * passes through untouched, and {@link #restore(String, Masked)} substitutes each sentinel with the
 * exact configured {@code Translated_Text}. Because the term never reaches the provider, the
 * configured translation always appears in the output byte-for-byte.
 *
 * <p>The glossary is keyed by target {@link LanguageTag}; terms configured for one language never
 * affect another. Term matching is longest-match-first and case-sensitive over the exact configured
 * spelling. Instances are immutable and thread-safe; {@link #empty()} is the default (no glossary
 * configured), under which {@link #mask} returns its input unchanged and translation behaves exactly
 * as before.
 *
 * <p>Wiring: a {@link DomainGlossary} is a Spring bean (see {@link TranslationConfig}) injected into
 * {@link DefaultTranslationService}; tests can construct one directly with {@link #of(Map)} and pass
 * it through the {@link DefaultTranslationService#DefaultTranslationService(List, TranslationCache,
 * TranslationRuntimeConfig, SupportedLanguages, TechnicalTokenProtector, DomainGlossary)} test
 * constructor.
 */
public final class DomainGlossary {

    /** Opening bracket of a glossary sentinel: MATHEMATICAL LEFT WHITE SQUARE BRACKET (U+27E6). */
    private static final char SENTINEL_OPEN = '\u27E6';
    /** Closing bracket of a glossary sentinel: MATHEMATICAL RIGHT WHITE SQUARE BRACKET (U+27E7). */
    private static final char SENTINEL_CLOSE = '\u27E7';
    /** Marker inside a glossary sentinel; distinct from the {@code IG} Technical_Token marker. */
    private static final String SENTINEL_MARK = "GLOSS";

    /** Per-target-language term &rarr; configured Translated_Text maps. */
    private final Map<LanguageTag, Map<String, String>> byLanguage;

    private DomainGlossary(Map<LanguageTag, Map<String, String>> byLanguage) {
        Map<LanguageTag, Map<String, String>> copy = new LinkedHashMap<>();
        byLanguage.forEach((tag, terms) -> {
            Map<String, String> validTerms = new LinkedHashMap<>();
            terms.forEach((term, translation) -> {
                if (term != null && !term.isEmpty() && translation != null) {
                    validTerms.put(term, translation);
                }
            });
            if (!validTerms.isEmpty()) {
                copy.put(tag, validTerms);
            }
        });
        this.byLanguage = copy;
    }

    /**
     * The empty glossary: no terms configured for any language. Under this glossary
     * {@link #mask(String, LanguageTag)} returns its input unchanged and translation behaves exactly
     * as it did before glossary support (Req 12.2 is inert when unconfigured).
     *
     * @return a glossary with no configured terms
     */
    public static DomainGlossary empty() {
        return new DomainGlossary(Map.of());
    }

    /**
     * Builds a glossary from a per-target-language term map.
     *
     * @param byLanguage target {@link LanguageTag} &rarr; (term &rarr; configured Translated_Text);
     *                   {@code null} yields the {@link #empty() empty} glossary
     * @return an immutable {@link DomainGlossary}
     */
    public static DomainGlossary of(Map<LanguageTag, Map<String, String>> byLanguage) {
        return byLanguage == null ? empty() : new DomainGlossary(byLanguage);
    }

    /**
     * Convenience factory for a glossary configured for a single target language.
     *
     * @param target the target {@link LanguageTag}
     * @param terms  the term &rarr; configured Translated_Text map for that language
     * @return an immutable {@link DomainGlossary}
     */
    public static DomainGlossary forLanguage(LanguageTag target, Map<String, String> terms) {
        if (target == null || terms == null || terms.isEmpty()) {
            return empty();
        }
        Map<LanguageTag, Map<String, String>> byLanguage = new LinkedHashMap<>();
        byLanguage.put(target, terms);
        return new DomainGlossary(byLanguage);
    }

    /**
     * Whether any glossary term is configured for the given target language.
     *
     * @param target the target {@link LanguageTag}
     * @return {@code true} when at least one term is configured for {@code target}
     */
    public boolean isConfiguredFor(LanguageTag target) {
        Map<String, String> terms = byLanguage.get(target);
        return terms != null && !terms.isEmpty();
    }

    /**
     * Replaces every configured glossary term for {@code target} with an opaque sentinel, recording
     * the configured {@code Translated_Text} for each replacement so {@link #restore} can substitute
     * it back. Matching is longest-first and case-sensitive; matches are non-overlapping and scanned
     * left to right.
     *
     * @param text   the (already Technical_Token-masked) text about to be sent to the provider;
     *               {@code null} is treated as empty
     * @param target the target {@link LanguageTag}
     * @return a {@link Masked} pairing the sentinel-substituted text with the ordered configured
     *         translations; when no term is configured for {@code target} the text is returned
     *         unchanged with no replacements
     */
    public Masked mask(String text, LanguageTag target) {
        String source = text == null ? "" : text;
        Map<String, String> terms = byLanguage.get(target);
        if (terms == null || terms.isEmpty() || source.isEmpty()) {
            return new Masked(source, List.of());
        }
        List<String> ordered = new ArrayList<>(terms.keySet());
        ordered.sort(Comparator.comparingInt(String::length).reversed());

        StringBuilder masked = new StringBuilder(source.length());
        List<String> replacements = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            String matched = null;
            for (String term : ordered) {
                if (source.regionMatches(i, term, 0, term.length())) {
                    matched = term;
                    break;
                }
            }
            if (matched == null) {
                masked.append(source.charAt(i));
                i++;
            } else {
                masked.append(sentinel(replacements.size()));
                replacements.add(terms.get(matched));
                i += matched.length();
            }
        }
        return new Masked(masked.toString(), replacements);
    }

    /**
     * Substitutes each glossary sentinel in the provider's output back with the exact configured
     * {@code Translated_Text}, guaranteeing each configured term is rendered as its configured
     * translation regardless of how the provider rewrote the surrounding prose (Req 12.2).
     *
     * @param providerOutput the provider's translation of the masked text; {@code null} yields
     *                       {@code null}
     * @param masked         the {@link Masked} produced by {@link #mask(String, LanguageTag)}
     * @return the text with every surviving glossary sentinel replaced by its configured translation
     */
    public String restore(String providerOutput, Masked masked) {
        if (providerOutput == null) {
            return null;
        }
        if (masked == null || masked.replacements().isEmpty()) {
            return providerOutput;
        }
        String result = providerOutput;
        List<String> replacements = masked.replacements();
        for (int i = 0; i < replacements.size(); i++) {
            result = result.replace(sentinel(i), replacements.get(i));
        }
        return result;
    }

    private static String sentinel(int index) {
        return SENTINEL_OPEN + SENTINEL_MARK + index + SENTINEL_CLOSE;
    }

    /**
     * The result of {@link DomainGlossary#mask(String, LanguageTag) masking} a text against the
     * glossary: the sentinel-substituted {@link #masked() text} and the ordered configured
     * {@link #replacements() translations} (the translation at index {@code i} replaces the sentinel
     * carrying index {@code i}).
     *
     * @param masked       the text with each configured term replaced by its sentinel
     * @param replacements the configured Translated_Text values, indexed to match their sentinels
     */
    public record Masked(String masked, List<String> replacements) {

        public Masked {
            if (masked == null) {
                throw new IllegalArgumentException("masked must not be null");
            }
            replacements = replacements == null ? List.of() : List.copyOf(replacements);
        }
    }
}
