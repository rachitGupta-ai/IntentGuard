package com.intentguard.e2e;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.intentguard.llm.LlmProperties;
import com.intentguard.speech.AudioClip;
import com.intentguard.speech.GeminiSpeechProvider;
import com.intentguard.speech.RecognizedText;
import com.intentguard.speech.SpeechProperties;
import com.intentguard.translation.GeminiTranslationProvider;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.TranslationProperties;

/**
 * End-to-end integration tests exercising the Gemini Translation Provider
 * against the live Gemini API. Skipped when GEMINI_API_KEY is not set.
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiTranslationE2eTest {

    private static final System.Logger log = System.getLogger(GeminiTranslationE2eTest.class.getName());
    private static final long TIMEOUT_MS = 30_000;
    private static final double SIMILARITY_THRESHOLD = 0.6;

    private static CommandCorpus corpus;

    private GeminiTranslationProvider translationProvider;
    private GeminiSpeechProvider speechProvider;

    @BeforeAll
    static void loadCorpus() {
        corpus = CommandCorpus.load(); // fails fast if missing (Req 6.5)
    }

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("GEMINI_API_KEY");

        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey(apiKey);
        llmProperties.setModel("gemini-2.5-flash");

        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setApiKey(apiKey);
        translationProperties.setTimeoutMs(TIMEOUT_MS);

        translationProvider = new GeminiTranslationProvider(llmProperties, translationProperties);

        SpeechProperties speechProperties = new SpeechProperties();
        speechProperties.setSttTimeoutMs(30000);
        speechProperties.setTtsTimeoutMs(10000);

        speechProvider = new GeminiSpeechProvider(llmProperties, speechProperties);
    }

    // --- Text Translation Scenarios (Req 1) ---

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" → \"{2}\"")
    @MethodSource("textTranslationTestCases")
    void textTranslation_producesValidLinuxCommand(String languageTag, String indianText,
                                                    String expectedEnglish) {
        LanguageTag source = LanguageTag.of(languageTag);
        LanguageTag target = LanguageTag.of("en");

        // First attempt
        Optional<String> result = translationProvider.translate(indianText, source, target);

        // Retry once if empty (Req 5.5)
        if (result.isEmpty()) {
            log.log(System.Logger.Level.INFO,
                    "First translation attempt returned empty for [{0}] \"{1}\", retrying...",
                    languageTag, indianText);
            result = translationProvider.translate(indianText, source, target);
        }

        // If still empty after 2 attempts, fail with diagnostic (Req 5.6)
        if (result.isEmpty()) {
            fail(String.format("""
                    Translation produced no output after 2 attempts:
                      Source language: %s
                      Input command:   %s
                      Expected:        %s""",
                    languageTag, indianText, expectedEnglish));
            return;
        }

        String translated = result.get();

        // Log each translation at INFO level (Req 5.4)
        log.log(System.Logger.Level.INFO,
                "Translated [{0}] \"{1}\" → \"{2}\"",
                languageTag, indianText, translated);

        // Assert similarity >= 0.6 (Req 5.2, 5.3)
        double score = CommandValidator.similarity(translated, expectedEnglish);
        assertTrue(score >= SIMILARITY_THRESHOLD,
                String.format("""
                        Translation validation failed:
                          Source language: %s
                          Input text:      %s
                          Expected:        %s
                          Actual:          %s
                          Similarity:      %.4f""",
                        languageTag, indianText, expectedEnglish, translated, score));

        // Assert structural validity (Req 1.4)
        assertTrue(CommandValidator.isStructurallyValid(translated),
                String.format("""
                        Translation is not structurally valid:
                          Source language: %s
                          Input text:      %s
                          Expected:        %s
                          Actual:          %s""",
                        languageTag, indianText, expectedEnglish, translated));
    }

    // --- Test Case Providers ---

    static Stream<Arguments> textTranslationTestCases() {
        CommandCorpus localCorpus = CommandCorpus.load();
        return localCorpus.entries().stream()
                .flatMap(entry -> entry.translations().entrySet().stream()
                        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                        .map(e -> Arguments.of(e.getKey(), e.getValue(), entry.englishCommand())));
    }

    // --- Audio Translation Scenarios (Req 3) ---

    @ParameterizedTest(name = "[{index}] {0} cmd#{1}: → \"{2}\"")
    @MethodSource("audioTranslationTestCases")
    void audioTranslation_producesExpectedCommand(String languageTag, int commandIndex,
                                                   String indianText, String expectedEnglish) {
        // 1. Load or synthesize audio fixture
        AudioClip audioClip = AudioFixtureLoader.loadOrSynthesize(
                languageTag, commandIndex, indianText, speechProvider);

        // 2. Recognize via GeminiSpeechProvider (Req 3.1, 3.2, 3.3, 3.7)
        Optional<RecognizedText> recognized = speechProvider.recognize(
                audioClip, LanguageTag.of(languageTag));

        // 3. Fail with diagnostic if STT returns empty transcription (Req 3.5)
        if (recognized.isEmpty() || recognized.get().text().isBlank()) {
            fail(String.format("""
                    STT returned empty transcription:
                      Audio language:  %s
                      Command index:   %d
                      Expected:        %s
                      STT result:      empty""",
                    languageTag, commandIndex, expectedEnglish));
            return;
        }

        String transcription = recognized.get().text();

        log.log(System.Logger.Level.INFO,
                "STT [{0}] cmd#{1} → \"{2}\"",
                languageTag, commandIndex, transcription);

        // 4. Translate transcription → English (Req 3.1, 3.2, 3.3)
        Optional<String> translated = translationProvider.translate(
                transcription, LanguageTag.of(languageTag), LanguageTag.of("en"));

        // 5. Fail with diagnostic if translation of non-empty transcription returns empty (Req 3.6)
        if (translated.isEmpty() || translated.get().isBlank()) {
            fail(String.format("""
                    Translation of non-empty transcription returned empty:
                      Source language: %s
                      Transcription:   %s
                      Expected:        %s""",
                    languageTag, transcription, expectedEnglish));
            return;
        }

        String result = translated.get();

        log.log(System.Logger.Level.INFO,
                "Translated [{0}] \"{1}\" → \"{2}\"",
                languageTag, transcription, result);

        // 6. Assert case-insensitive trimmed equality (Req 3.1, 3.2, 3.3, 3.4)
        assertTrue(result.trim().equalsIgnoreCase(expectedEnglish.trim()),
                String.format("""
                        Audio translation mismatch:
                          Source language: %s
                          Command index:   %d
                          Transcription:   %s
                          Expected:        %s
                          Actual:          %s""",
                        languageTag, commandIndex, transcription, expectedEnglish, result));
    }

    // --- Audio Test Case Provider ---

    static Stream<Arguments> audioTranslationTestCases() {
        CommandCorpus localCorpus = CommandCorpus.load();
        List<String> targetLanguages = List.of("hi", "ta", "bn");
        List<Integer> targetIndices = List.of(1, 2, 3);

        return localCorpus.entries().stream()
                .filter(entry -> targetIndices.contains(entry.index()))
                .flatMap(entry -> targetLanguages.stream()
                        .filter(lang -> {
                            String translation = entry.translations().get(lang);
                            return translation != null && !translation.isBlank();
                        })
                        .map(lang -> Arguments.of(
                                lang,
                                entry.index(),
                                entry.translations().get(lang),
                                entry.englishCommand())));
    }
}
