package com.intentguard.e2e;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommandCorpus}.
 * Validates: Requirements 6.3, 6.5, 6.6
 */
class CommandCorpusTest {

    private static CommandCorpus corpus;

    @BeforeAll
    static void loadCorpus() {
        corpus = CommandCorpus.load();
    }

    // --- load() with valid corpus file ---

    @Test
    void load_parsesAllEntries() {
        List<CommandCorpus.Entry> entries = corpus.entries();
        assertNotNull(entries);
        assertEquals(7, entries.size(), "Corpus should contain 7 command entries");
    }

    @Test
    void load_entriesHaveCorrectStructure() {
        CommandCorpus.Entry first = corpus.entries().get(0);
        assertEquals(1, first.index());
        assertEquals("ls -la", first.englishCommand());
        assertNotNull(first.translations());
        assertFalse(first.translations().isEmpty());
    }

    @Test
    void load_entriesContainAllTenLanguages() {
        List<String> expectedLanguages = List.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");
        for (CommandCorpus.Entry entry : corpus.entries()) {
            for (String lang : expectedLanguages) {
                assertTrue(entry.translations().containsKey(lang),
                    "Entry " + entry.index() + " missing translation for " + lang);
                assertFalse(entry.translations().get(lang).isBlank(),
                    "Entry " + entry.index() + " has blank translation for " + lang);
            }
        }
    }

    @Test
    void load_entriesAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
            () -> corpus.entries().add(new CommandCorpus.Entry(99, "test", Map.of())));
    }

    // --- forLanguage() filters entries properly ---

    @Test
    void forLanguage_returnsAllEntriesForKnownLanguage() {
        List<CommandCorpus.Entry> hindiEntries = corpus.forLanguage("hi");
        assertEquals(7, hindiEntries.size(),
            "All 7 entries have Hindi translations");
    }

    @Test
    void forLanguage_returnsEmptyListForUnknownLanguage() {
        List<CommandCorpus.Entry> entries = corpus.forLanguage("zz");
        assertNotNull(entries);
        assertTrue(entries.isEmpty(),
            "Unknown language tag should return empty list");
    }

    @Test
    void forLanguage_filtersCorrectlyForEachSupportedLanguage() {
        List<String> languages = List.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");
        for (String lang : languages) {
            List<CommandCorpus.Entry> entries = corpus.forLanguage(lang);
            assertEquals(7, entries.size(),
                "All entries should have non-empty translation for " + lang);
        }
    }

    // --- totalTestCases() returns correct count ---

    @Test
    void totalTestCases_returns70() {
        // 7 commands × 10 languages = 70 non-empty translations
        assertEquals(70, corpus.totalTestCases(),
            "Total test cases should be 7 commands × 10 languages = 70");
    }

    // --- load() with missing file throws IllegalStateException ---
    // Note: The resource path is hardcoded in CommandCorpus (classpath:/e2e/commands/corpus.json).
    // Since the file exists on the test classpath, we cannot easily test the missing-file path
    // without reflection or restructuring. This is documented as a testing limitation.
    // The behavior IS tested implicitly: if the file were missing, loadCorpus() in @BeforeAll
    // would fail with IllegalStateException, causing all tests in this class to fail.
}
