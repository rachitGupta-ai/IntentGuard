package com.intentguard.translation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The configured {@code Supported_Language} set and its native-script display names (Req 6.1, 6.2).
 *
 * <p>The default set is English plus the ten Indian languages named in Req 6.2: Hindi ({@code hi}),
 * Bengali ({@code bn}), Telugu ({@code te}), Marathi ({@code mr}), Tamil ({@code ta}), Gujarati
 * ({@code gu}), Kannada ({@code kn}), Malayalam ({@code ml}), Punjabi ({@code pa}), and Odia
 * ({@code or}). English is the default Language_Preference.
 *
 * <p>This holder is the single source of truth for the unsupported-language guard and for STT/TTS
 * language acceptance: {@link #isSupported(LanguageTag)} is the membership check consumed by the
 * {@code TranslationService} guard (Req 6.4) and the {@code SpeechService}. Each supported tag
 * carries a display name in that language's native script, stored and returned as UTF-8 (Req 6.3).
 *
 * <p>Instances are immutable; the {@link #defaults()} factory returns the standard set.
 */
public final class SupportedLanguages {

    /** The default Language_Preference tag (English) (Req 1.3). */
    public static final LanguageTag ENGLISH = LanguageTag.of("en");

    private final Map<LanguageTag, String> displayNames;

    private SupportedLanguages(Map<LanguageTag, String> displayNames) {
        // Preserve insertion order for stable presentation of the selectable set (Req 1.1).
        this.displayNames = new LinkedHashMap<>(displayNames);
    }

    /**
     * Builds the default Supported_Language set (Req 6.2) with native-script display names (Req 6.3).
     *
     * @return the standard {@link SupportedLanguages} holder
     */
    public static SupportedLanguages defaults() {
        Map<LanguageTag, String> names = new LinkedHashMap<>();
        names.put(LanguageTag.of("en"), "English");
        names.put(LanguageTag.of("hi"), "\u0939\u093f\u0928\u094d\u0926\u0940");                 // हिन्दी
        names.put(LanguageTag.of("bn"), "\u09ac\u09be\u0982\u09b2\u09be");                         // বাংলা
        names.put(LanguageTag.of("te"), "\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41");                   // తెలుగు
        names.put(LanguageTag.of("mr"), "\u092e\u0930\u093e\u0920\u0940");                         // मराठी
        names.put(LanguageTag.of("ta"), "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd");                         // தமிழ்
        names.put(LanguageTag.of("gu"), "\u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4\u0ac0");             // ગુજરાતી
        names.put(LanguageTag.of("kn"), "\u0c95\u0ca8\u0ccd\u0ca8\u0ca1");                         // ಕನ್ನಡ
        names.put(LanguageTag.of("ml"), "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02");                   // മലയാളം
        names.put(LanguageTag.of("pa"), "\u0a2a\u0a70\u0a1c\u0a3e\u0a2c\u0a40");                   // ਪੰਜਾਬੀ
        names.put(LanguageTag.of("or"), "\u0b13\u0b21\u0b3f\u0b06");                               // ଓଡ଼ିଆ
        return new SupportedLanguages(names);
    }

    /**
     * Membership check used by the unsupported-language guard (Req 6.4) and STT/TTS acceptance.
     *
     * @param tag the language tag to check; may be {@code null}
     * @return {@code true} when {@code tag} is a member of this Supported_Language set
     */
    public boolean isSupported(LanguageTag tag) {
        return tag != null && displayNames.containsKey(tag);
    }

    /**
     * Returns the native-script display name for a supported tag (Req 6.3).
     *
     * @param tag the language tag
     * @return the display name, or empty when the tag is not supported
     */
    public Optional<String> displayName(LanguageTag tag) {
        return Optional.ofNullable(tag == null ? null : displayNames.get(tag));
    }

    /**
     * The full Supported_Language set, in presentation order (Req 1.1).
     *
     * @return an unmodifiable set of the supported tags
     */
    public Set<LanguageTag> tags() {
        return Set.copyOf(displayNames.keySet());
    }

    /**
     * The number of languages in this set.
     *
     * @return the count of supported languages
     */
    public int size() {
        return displayNames.size();
    }
}
