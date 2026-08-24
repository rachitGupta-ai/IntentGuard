package com.intentguard.translation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the default {@code Supported_Language} set (Req 6.2).
 *
 * <p>Asserts that all eleven languages (English plus the ten Indian languages) are present with the
 * correct BCP-47 tags and non-empty native-script display names.
 */
class SupportedLanguagesTest {

    private static final List<String> EXPECTED_TAGS =
            List.of("en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");

    @Test
    void defaultsContainAllElevenSupportedLanguages() {
        SupportedLanguages languages = SupportedLanguages.defaults();

        assertThat(languages.size()).isEqualTo(11);
        assertThat(languages.tags()).hasSize(11);
    }

    @Test
    void defaultsContainEachExpectedBcp47Tag() {
        SupportedLanguages languages = SupportedLanguages.defaults();

        for (String tag : EXPECTED_TAGS) {
            LanguageTag languageTag = LanguageTag.of(tag);
            assertThat(languages.isSupported(languageTag))
                    .as("tag '%s' should be supported", tag)
                    .isTrue();
        }
    }

    @Test
    void defaultsContainExactlyTheExpectedTags() {
        SupportedLanguages languages = SupportedLanguages.defaults();

        List<String> actualTags = languages.tags().stream().map(LanguageTag::value).sorted().toList();
        List<String> expectedSorted = EXPECTED_TAGS.stream().sorted().toList();

        assertThat(actualTags).isEqualTo(expectedSorted);
    }

    @Test
    void everySupportedTagHasNonEmptyNativeScriptDisplayName() {
        SupportedLanguages languages = SupportedLanguages.defaults();

        for (String tag : EXPECTED_TAGS) {
            LanguageTag languageTag = LanguageTag.of(tag);
            assertThat(languages.displayName(languageTag))
                    .as("display name for '%s' should be present", tag)
                    .isPresent();
            assertThat(languages.displayName(languageTag).orElseThrow().trim())
                    .as("display name for '%s' should be non-empty", tag)
                    .isNotEmpty();
        }
    }

    @Test
    void indianLanguageDisplayNamesUseNonAsciiNativeScript() {
        SupportedLanguages languages = SupportedLanguages.defaults();

        // English is ASCII; every Indian language display name must be in its native (non-ASCII) script.
        List<String> indianTags = List.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");
        for (String tag : indianTags) {
            String name = languages.displayName(LanguageTag.of(tag)).orElseThrow();
            assertThat(name.chars().anyMatch(c -> c > 127))
                    .as("display name for '%s' ('%s') should contain native-script (non-ASCII) characters", tag, name)
                    .isTrue();
        }
    }

    @Test
    void englishIsTheDefaultPreferenceTag() {
        assertThat(SupportedLanguages.ENGLISH.value()).isEqualTo("en");
        assertThat(SupportedLanguages.defaults().isSupported(SupportedLanguages.ENGLISH)).isTrue();
    }
}
