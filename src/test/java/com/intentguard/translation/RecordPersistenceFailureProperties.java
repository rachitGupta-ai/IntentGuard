// Feature: indian-language-translation, Property 14: A record-persistence failure still presents the Translated_Text
package com.intentguard.translation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.speech.SpeechProperties;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 14: A record-persistence failure still presents the
 * Translated_Text.
 *
 * <p>For any successful translation whose Translation_Record cannot be persisted, the Control_Tower
 * still presents the Translated_Text (Validates: Requirements 10.2).
 *
 * <p>This exercises the {@link DefaultTranslationService} success seam
 * ({@code onTranslated}) which persists a {@link TranslationRecord} on a successful translation but
 * catches any persistence {@link RuntimeException} so the write can never block presentation. The
 * service is wired — through its Spring constructor taking an {@link ObjectProvider} of
 * {@link TranslationRecordRepository} — to a <strong>failing</strong> repository whose
 * {@link TranslationRecordRepository#save(TranslationRecord) save} always throws. Despite that
 * persistence failure, the translate result must still report outcome
 * {@link TranslationOutcome#TRANSLATED}, {@code translated == true}, and a non-null Translated_Text
 * equal to what the provider produced/restored — proving the failed write did not corrupt or block
 * presenting the Translated_Text.
 *
 * <p>Translation itself is made to succeed with a counting {@link PassthroughTranslationProvider}
 * that returns the masked text unchanged, so every sentinel inserted by
 * {@link TechnicalTokenProtector} survives and the restore reproduces the Source_Text
 * byte-for-byte; the runtime config's active Translation_Provider id matches the fake's id so the
 * translation is genuinely served by the provider (reaching the persistence seam). The expected
 * presented text is derived from a control service wired to <em>no</em> repository (no persistence),
 * so the assertion depends only on the observable equality of the presented text with and without a
 * failing write — not on any internal masking detail.
 */
class RecordPersistenceFailureProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 14: A record-persistence failure still presents the Translated_Text
    @Property(tries = 200)
    void recordPersistenceFailureStillPresentsTheTranslatedText(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("supportedNonEnglishTags") LanguageTag targetLang) {

        FailingTranslationRecordRepository failingRepository = new FailingTranslationRecordRepository();
        DefaultTranslationService service = serviceWith(new PassthroughTranslationProvider(), failingRepository);

        TranslationResult result = service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang);

        // Despite the persistence failure, the translation succeeded and its Translated_Text is presented.
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.translated()).isTrue();
        assertThat(result.text()).isNotNull();

        // The failed write was genuinely attempted (the persistence seam was reached and threw)...
        assertThat(failingRepository.saveAttempts()).isPositive();

        // ...yet the presented Translated_Text is exactly what a translation with NO persistence
        // produces: the failure did not block or alter presenting the Translated_Text.
        DefaultTranslationService control =
                serviceWith(new PassthroughTranslationProvider(), (TranslationRecordRepository) null);
        TranslationResult expected = control.translate(sourceText, SupportedLanguages.ENGLISH, targetLang);
        assertThat(result.text()).isEqualTo(expected.text());
    }

    // ---- Worked example ---------------------------------------------------------------------------

    @Example
    void failingRepositoryDoesNotBlockPresentingTheTranslatedText() {
        FailingTranslationRecordRepository failingRepository = new FailingTranslationRecordRepository();
        DefaultTranslationService service = serviceWith(new PassthroughTranslationProvider(), failingRepository);

        String source = "please review the alert for session-42 with score 0.91";
        LanguageTag target = LanguageTag.of("hi");

        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, target);

        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.translated()).isTrue();
        assertThat(result.text()).isNotNull();
        assertThat(failingRepository.saveAttempts()).isPositive();

        // Technical_Tokens are preserved and the presented text matches the no-persistence baseline.
        assertThat(result.text()).contains("session-42").contains("0.91");
        DefaultTranslationService control =
                serviceWith(new PassthroughTranslationProvider(), (TranslationRecordRepository) null);
        assertThat(result.text())
                .isEqualTo(control.translate(source, SupportedLanguages.ENGLISH, target).text());
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} directly (no Spring context) around the given
     * counting fake and the given {@link TranslationRecordRepository}, seeding a
     * {@link TranslationRuntimeConfig} whose active Translation_Provider id matches the fake's id so
     * the request is genuinely served by the provider and reaches the persistence seam. The
     * repository is supplied through a minimal {@link ObjectProvider} test double so the
     * Spring-injected constructor (the one that persists a {@link TranslationRecord}) is exercised.
     */
    private DefaultTranslationService serviceWith(
            PassthroughTranslationProvider provider, TranslationRecordRepository recordRepository) {
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
                new SingletonObjectProvider<>(recordRepository));
    }

    /**
     * A {@link TranslationRecordRepository} whose {@link #save} always throws, simulating a
     * record-persistence failure (Req 10.2). It is constructed over a mocked {@link MongoDatabase}
     * so the real collection is never touched, and counts each attempted write so the test can prove
     * the persistence seam was genuinely reached before the failure.
     */
    static final class FailingTranslationRecordRepository extends TranslationRecordRepository {

        private final AtomicInteger saveAttempts = new AtomicInteger();

        FailingTranslationRecordRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(TranslationRecord record) {
            saveAttempts.incrementAndGet();
            throw new RuntimeException("simulated Translation_Record persistence failure");
        }

        int saveAttempts() {
            return saveAttempts.get();
        }
    }

    /**
     * Minimal {@link ObjectProvider} test double returning a single (possibly {@code null}) instance.
     * The service resolves the repository via {@link ObjectProvider#getIfAvailable()}.
     */
    static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Non-empty content: plain prose optionally mixed with Technical_Tokens. The passthrough fake
        // preserves the masked sentinels, so the translation always succeeds as TRANSLATED and the
        // persistence seam (which then fails) is reached.
        Arbitrary<String> tokens = Arbitraries.of(
                "/etc/passwd", "rm -rf /tmp/cache", "db.prod.internal", "0.87",
                "session-42", "2024-01-02T10:00:00Z", "DUAL_CONTROL_REQUIRED");
        Arbitrary<String> words = Arbitraries.of(
                "the", "operator", "should", "review", "this", "alert", "before", "approval");
        return Arbitraries.oneOf(words, words, tokens).list().ofMinSize(1).ofMaxSize(6)
                .map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<LanguageTag> supportedNonEnglishTags() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }
}
