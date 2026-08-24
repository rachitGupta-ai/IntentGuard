package com.intentguard.translation;

import java.util.Locale;

/**
 * A BCP-47 language tag identifying a {@code Supported_Language} (Req 6.1).
 *
 * <p>The tag is the single source of truth used by the unsupported-language guard
 * ({@link SupportedLanguages#isSupported(LanguageTag)}) and by STT/TTS language acceptance. The
 * stored {@link #value()} is normalized to lower case so that lookups against the
 * {@link SupportedLanguages} set are case-insensitive (for example {@code "HI"} and {@code "hi"}
 * denote the same language).
 *
 * @param value the non-blank BCP-47 tag, for example {@code "en"}, {@code "hi"}, or {@code "bn"}
 */
public record LanguageTag(String value) {

    public LanguageTag {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LanguageTag value must be non-blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Convenience factory mirroring the record constructor for readable call sites.
     *
     * @param value the BCP-47 tag
     * @return a normalized {@link LanguageTag}
     */
    public static LanguageTag of(String value) {
        return new LanguageTag(value);
    }
}
