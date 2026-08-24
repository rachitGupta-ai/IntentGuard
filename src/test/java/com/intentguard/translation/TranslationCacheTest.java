package com.intentguard.translation;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TranslationCache} — the translation reuse cache keyed by
 * {@code (Source_Text, target Supported_Language)} (Req 9.3).
 */
class TranslationCacheTest {

    private static final LanguageTag HINDI = LanguageTag.of("hi");
    private static final LanguageTag BENGALI = LanguageTag.of("bn");

    @Test
    void lookupReturnsEmptyWhenNothingStored() {
        TranslationCache cache = new TranslationCache();

        assertThat(cache.lookup("run backup", HINDI)).isEmpty();
    }

    @Test
    void lookupReturnsStoredTranslatedText() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", HINDI, "बैकअप चलाएँ");

        assertThat(cache.lookup("run backup", HINDI)).contains("बैकअप चलाएँ");
    }

    @Test
    void keyDiscriminatesOnTargetLanguage() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", HINDI, "बैकअप चलाएँ");

        assertThat(cache.lookup("run backup", HINDI)).contains("बैकअप चलाएँ");
        assertThat(cache.lookup("run backup", BENGALI)).isEmpty();
    }

    @Test
    void keyDiscriminatesOnSourceText() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", HINDI, "बैकअप चलाएँ");

        assertThat(cache.lookup("delete backup", HINDI)).isEmpty();
    }

    @Test
    void storeOverwritesExistingEntryForSameKey() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", HINDI, "first");
        cache.store("run backup", HINDI, "second");

        assertThat(cache.lookup("run backup", HINDI)).contains("second");
    }

    @Test
    void sameSourceCachedIndependentlyPerLanguage() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", HINDI, "बैकअप चलाएँ");
        cache.store("run backup", BENGALI, "ব্যাকআপ চালান");

        assertThat(cache.lookup("run backup", HINDI)).contains("बैकअप चलाएँ");
        assertThat(cache.lookup("run backup", BENGALI)).contains("ব্যাকআপ চালান");
    }

    @Test
    void lookupIsCaseInsensitiveOnLanguageTagViaNormalization() {
        TranslationCache cache = new TranslationCache();

        cache.store("run backup", LanguageTag.of("HI"), "बैकअप चलाएँ");

        Optional<String> found = cache.lookup("run backup", LanguageTag.of("hi"));
        assertThat(found).contains("बैकअप चलाएँ");
    }
}
