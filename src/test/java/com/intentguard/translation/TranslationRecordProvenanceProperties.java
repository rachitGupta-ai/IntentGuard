// Feature: indian-language-translation, Property 13: Translation_Records capture the full provenance
package com.intentguard.translation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import com.intentguard.speech.SpeechProperties;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 13: Translation_Records capture the full
 * provenance.
 *
 * <p>For any successful translation, the persisted {@link TranslationRecord} contains the
 * Source_Text, the Translated_Text, the source and target {@code Supported_Language} tags, and the
 * identity of the Translation_Provider used (Validates: Requirements 10.1, 8.7).
 *
 * <p>This exercises the {@link DefaultTranslationService#onTranslated persistence seam} reached only
 * on a successful {@link TranslationOutcome#TRANSLATED} translation. The service is wired via its
 * Spring-injected constructor with an {@link ObjectProvider} wrapping an in-memory
 * {@link InMemoryTranslationRecordRepository} that captures each saved record, and a counting
 * {@link PassthroughTranslationProvider} whose masked-text-preserving output guarantees every
 * Technical_Token is restored byte-for-byte so the first call always succeeds as {@code TRANSLATED}
 * (rather than falling through a token-integrity path). After one successful translate, exactly one
 * record must be persisted and it must carry the full provenance: the original Source_Text, the
 * Translated_Text equal to {@link TranslationResult#text()}, the source ({@code en}) and target
 * tags, and the provider identity ({@code == provider.id()}).
 */
class TranslationRecordProvenanceProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 13: Translation_Records capture the full provenance
    @Property(tries = 200)
    void persistedRecordCapturesFullProvenance(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("supportedNonEnglishTags") LanguageTag targetLang) {

        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        InMemoryTranslationRecordRepository repository = new InMemoryTranslationRecordRepository();
        DefaultTranslationService service = serviceWith(provider, repository);

        TranslationResult result =
                service.translate(sourceText, SupportedLanguages.ENGLISH, targetLang);

        // A genuine translation was produced (precondition for a record being written).
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);
        assertThat(result.translated()).isTrue();

        // Exactly one Translation_Record was persisted for the successful translation.
        assertThat(repository.saved()).hasSize(1);
        TranslationRecord record = repository.saved().get(0);

        // Full provenance (Req 10.1, 8.7): Source_Text, Translated_Text, source/target tags, provider id.
        assertThat(record.sourceText()).isEqualTo(sourceText);
        assertThat(record.translatedText()).isEqualTo(result.text());
        assertThat(record.sourceLanguageTag()).isEqualTo(SupportedLanguages.ENGLISH);
        assertThat(record.targetLanguageTag()).isEqualTo(targetLang);
        assertThat(record.providerId()).isEqualTo(provider.id());
    }

    // ---- Worked example ---------------------------------------------------------------------------

    @Example
    void successfulTranslationPersistsFullProvenance() {
        PassthroughTranslationProvider provider = new PassthroughTranslationProvider();
        InMemoryTranslationRecordRepository repository = new InMemoryTranslationRecordRepository();
        DefaultTranslationService service = serviceWith(provider, repository);

        String source = "please review the alert for session-42 with score 0.91";
        LanguageTag target = LanguageTag.of("hi");

        TranslationResult result = service.translate(source, SupportedLanguages.ENGLISH, target);
        assertThat(result.outcome()).isEqualTo(TranslationOutcome.TRANSLATED);

        assertThat(repository.saved()).hasSize(1);
        TranslationRecord record = repository.saved().get(0);
        assertThat(record.sourceText()).isEqualTo(source);
        assertThat(record.translatedText()).isEqualTo(result.text());
        assertThat(record.sourceLanguageTag()).isEqualTo(SupportedLanguages.ENGLISH);
        assertThat(record.targetLanguageTag()).isEqualTo(target);
        assertThat(record.providerId()).isEqualTo(provider.id());
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Builds a {@link DefaultTranslationService} through its Spring-injected constructor so the
     * successful-translation persistence seam is active, wrapping the in-memory record repository in
     * an {@link ObjectProvider} exactly as Spring would. The runtime config's active
     * Translation_Provider id matches the fake's id so the request is genuinely served by the
     * provider, alongside the default Supported_Language set, a fresh cache, and the real
     * {@link TechnicalTokenProtector}.
     */
    private DefaultTranslationService serviceWith(
            PassthroughTranslationProvider provider, TranslationRecordRepository repository) {
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
                SingletonObjectProvider.of(repository));
    }

    // ---- Generators -------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Non-empty content: plain prose optionally mixed with Technical_Tokens. The passthrough fake
        // preserves the masked sentinels, so the translation always succeeds as TRANSLATED and a
        // record is written.
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

    /**
     * Minimal {@link ObjectProvider} test double wrapping a single instance, mirroring the decision
     * package's {@code SingletonObjectProvider}, used to wire the in-memory record repository into
     * {@link DefaultTranslationService} exactly as Spring would.
     */
    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        private SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        static <T> SingletonObjectProvider<T> of(T instance) {
            return new SingletonObjectProvider<>(instance);
        }

        @Override
        public T getObject() {
            if (instance == null) {
                throw new NoSuchBeanDefinitionException("no instance available");
            }
            return instance;
        }

        @Override
        public T getObject(Object... args) {
            return getObject();
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
}
