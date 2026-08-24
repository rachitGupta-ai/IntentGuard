// Feature: indian-language-translation, Property 7: Inbound Declared_Intent opens the session on English and records both texts
package com.intentguard.intent;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.domain.Actor;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.speech.SpeechProperties;
import com.intentguard.translation.DefaultTranslationService;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.MaskedText;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;
import com.intentguard.translation.TranslationCache;
import com.intentguard.translation.TranslationProperties;
import com.intentguard.translation.TranslationProvider;
import com.intentguard.translation.TranslationRuntimeConfig;
import com.intentguard.translation.TranslationService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 7: Inbound Declared_Intent opens the session on
 * English and records both texts.
 *
 * <p>For any Declared_Intent submitted in a non-English {@code Supported_Language}, the
 * Intent_Session is opened using the translated Engine_Language (English) text, and the session
 * records both the original Source_Text and the English text with its BCP-47 language tag
 * (Validates: Requirements 3.1, 3.2, 10.4).
 *
 * <p>The {@link InboundIntentService} is constructed directly (no Spring) around:
 * <ul>
 *   <li>a real {@link DefaultTranslationService} whose active provider is an
 *       {@link EnglishMarkingTranslationProvider} — a deterministic, sentinel-preserving fake that
 *       produces a <em>distinct</em> English translation (so the English text is genuinely different
 *       from the Source_Text) while leaving every {@code TechnicalToken} sentinel byte-for-byte
 *       intact, guaranteeing a {@code TRANSLATED} outcome;</li>
 *   <li>a real {@link DefaultIntentSessionManager} whose repositories are mocked (the human
 *       open-path never reads back from the repository nor writes an audit record, so a no-op mock
 *       faithfully exercises the manager's real recording logic);</li>
 *   <li>a mocked {@link LanguagePreferenceService} (only reached on the rejection path, which this
 *       success-only property never exercises).</li>
 * </ul>
 *
 * <p>The expected English text is recomputed in-test with the same {@link TechnicalTokenProtector}
 * mask/restore the service uses (the service is wired with an empty glossary), so the assertion
 * that {@code session.declaredIntent()} equals the English text is exact and independent of the
 * service internals.
 */
class InboundIntentSessionProperties {

    private final SupportedLanguages supportedLanguages = SupportedLanguages.defaults();

    // Feature: indian-language-translation, Property 7: Inbound Declared_Intent opens the session on English and records both texts
    @Property(tries = 200)
    void inboundIntentOpensSessionOnEnglishAndRecordsBothTexts(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("nonEnglishTags") LanguageTag sourceTag,
            @ForAll("operatorIds") String operatorId) {

        InboundIntentService service = inboundServiceWithDistinctEnglish();

        InboundIntentResult result =
                service.submit(operatorId, sourceText, sourceTag, Actor.human(operatorId));

        // Req 3.1: a non-English Declared_Intent opens an Intent_Session.
        assertThat(result.opened()).isTrue();
        Optional<IntentSession> opened = result.openedSession();
        assertThat(opened).isPresent();
        IntentSession session = opened.orElseThrow();

        // The session is opened on the translated Engine_Language (English) text (Req 3.1), which is
        // genuinely distinct from the submitted Source_Text.
        String expectedEnglish = expectedEnglishTranslation(sourceText);
        assertThat(session.declaredIntent()).isEqualTo(expectedEnglish);
        assertThat(session.declaredIntent()).isNotEqualTo(sourceText);

        // Both texts are recorded on the session (Req 3.2, 10.4): the original untranslated
        // Source_Text and its BCP-47 source language tag alongside the English text.
        assertThat(session.originalDeclaredIntent()).isEqualTo(sourceText);
        assertThat(session.declaredIntentLanguageTag()).isEqualTo(sourceTag.value());

        // The session belongs to the submitting operator and is open with declared provenance.
        assertThat(session.userId()).isEqualTo(operatorId);
        assertThat(session.open()).isTrue();
    }

    // ---- Worked example --------------------------------------------------------------------------

    @Example
    void hindiIntentWithTechnicalTokensOpensOnEnglishAndRecordsBoth() {
        InboundIntentService service = inboundServiceWithDistinctEnglish();
        String source = "kripya review the alert for session-42 with score 0.91";
        LanguageTag hindi = LanguageTag.of("hi");

        InboundIntentResult result = service.submit("operator-1", source, hindi, Actor.human("operator-1"));

        assertThat(result.opened()).isTrue();
        IntentSession session = result.openedSession().orElseThrow();

        // Opened on the distinct English text; the Technical_Tokens survive byte-for-byte.
        assertThat(session.declaredIntent()).isEqualTo(expectedEnglishTranslation(source));
        assertThat(session.declaredIntent()).contains("session-42").contains("0.91");

        // Both texts recorded with the source tag (Req 3.2, 10.4).
        assertThat(session.originalDeclaredIntent()).isEqualTo(source);
        assertThat(session.declaredIntentLanguageTag()).isEqualTo("hi");
    }

    // ---- Wiring ----------------------------------------------------------------------------------

    private InboundIntentService inboundServiceWithDistinctEnglish() {
        TranslationService translationService = distinctEnglishTranslationService();

        // Real session manager; the human open-path only writes the session document (no read-back,
        // no audit record), so no-op mocks faithfully drive its real recording logic.
        IntentSessionManager sessionManager = new DefaultIntentSessionManager(
                mock(IntentSessionRepository.class), mock(AuditHistoryRepository.class));

        // Only consulted on the rejection path, which this success-only property never reaches.
        LanguagePreferenceService languagePreferenceService = mock(LanguagePreferenceService.class);

        return new InboundIntentService(translationService, sessionManager, languagePreferenceService);
    }

    private DefaultTranslationService distinctEnglishTranslationService() {
        EnglishMarkingTranslationProvider provider = new EnglishMarkingTranslationProvider();
        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, new SpeechProperties());
        runtimeConfig.initialize();
        return new DefaultTranslationService(
                java.util.List.of(provider),
                new TranslationCache(),
                runtimeConfig,
                supportedLanguages,
                new TechnicalTokenProtector());
    }

    /**
     * Recomputes the English text the service produces for {@code source}, mirroring its internal
     * mask &rarr; provider &rarr; restore flow with an empty glossary, so the equality assertion on
     * {@code session.declaredIntent()} is exact.
     */
    private static String expectedEnglishTranslation(String source) {
        TechnicalTokenProtector protector = new TechnicalTokenProtector();
        MaskedText masked = protector.mask(source);
        String providerOutput = EnglishMarkingTranslationProvider.translateMasked(masked.masked());
        return protector.restore(providerOutput, masked);
    }

    // ---- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Non-empty content mixing plain prose with Technical_Tokens. The provider preserves the
        // masked sentinels, so the inbound translation always succeeds as TRANSLATED.
        Arbitrary<String> tokens = Arbitraries.of(
                "/etc/passwd", "rm -rf /tmp/cache", "db.prod.internal", "0.91",
                "session-42", "2024-01-15T02:30:00Z", "DUAL_CONTROL_REQUIRED", "git status");
        Arbitrary<String> words = Arbitraries.of(
                "kripya", "operator", "dekhein", "review", "alert", "sandesh", "before", "approval");
        return Arbitraries.oneOf(words, words, tokens).list().ofMinSize(1).ofMaxSize(6)
                .map(list -> String.join(" ", list));
    }

    @Provide
    Arbitrary<LanguageTag> nonEnglishTags() {
        return Arbitraries.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or")
                .map(LanguageTag::of);
    }

    @Provide
    Arbitrary<String> operatorIds() {
        return Arbitraries.of("operator-1", "alice", "bob", "carol", "ops-lead");
    }

    /**
     * Deterministic, sentinel-preserving {@link TranslationProvider} fake that simulates a genuine
     * translation into English: it prefixes an {@code "EN "} marker and uppercases the masked prose
     * so the produced text is <em>distinct</em> from the Source_Text, while the opaque
     * {@code ⟦IG#⟧} Technical_Token sentinels (which contain no lowercase letters) pass through
     * byte-for-byte — guaranteeing a {@code TRANSLATED} outcome with every Technical_Token preserved.
     */
    private static final class EnglishMarkingTranslationProvider implements TranslationProvider {

        @Override
        public String id() {
            return "english-marking-fake";
        }

        @Override
        public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
            return Optional.of(translateMasked(maskedText));
        }

        static String translateMasked(String maskedText) {
            return "EN " + maskedText.toUpperCase(Locale.ROOT);
        }
    }
}
