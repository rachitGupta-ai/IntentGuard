// Feature: indian-language-translation, Property 8: Inbound translation failure rejects the submission with no session
package com.intentguard.intent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intentguard.domain.Actor;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.speech.SpeechProperties;
import com.intentguard.translation.DefaultTranslationService;
import com.intentguard.translation.ErrorTranslationProvider;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TechnicalTokenProtector;
import com.intentguard.translation.TimeoutTranslationProvider;
import com.intentguard.translation.TranslationCache;
import com.intentguard.translation.TranslationProperties;
import com.intentguard.translation.TranslationProvider;
import com.intentguard.translation.TranslationRuntimeConfig;
import com.intentguard.translation.TranslationService;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: indian-language-translation, Property 8: Inbound translation failure rejects the
 * submission with no session.
 *
 * <p>For any inbound Declared_Intent for which the Translation_Provider times out or errors, the
 * submission is rejected, <strong>no</strong> Intent_Session is opened, and the operator is prompted
 * (in their Language_Preference) to retry or submit in English
 * (Validates: Requirements 3.3, 3.4).
 *
 * <p>The {@link InboundIntentService} is constructed directly (no Spring) around:
 * <ul>
 *   <li>a real {@link DefaultTranslationService} whose active provider is a failing fake — either an
 *       {@link ErrorTranslationProvider} (returns {@link Optional#empty()} &rarr;
 *       {@code PROVIDER_ERROR}, Req 3.4) or a {@link TimeoutTranslationProvider} whose delay far
 *       exceeds a deliberately small {@value #TIMEOUT_MS}ms budget (&rarr; {@code PROVIDER_TIMEOUT},
 *       Req 3.3). In both cases the api key and matching provider id are set so the text-translation
 *       capability is enabled — the failure is a genuine <em>provider</em> failure, not the startup
 *       capability gate;</li>
 *   <li>a real {@link DefaultIntentSessionManager} backed by an in-memory
 *       {@link IntentSessionRepository} fake and a no-op mocked {@link AuditHistoryRepository}, so we
 *       can assert directly that <strong>nothing</strong> was ever saved and no session is active;</li>
 *   <li>a mocked {@link LanguagePreferenceService} returning the operator's non-English preference,
 *       so the localized retry/English prompt is produced (Req 3.4).</li>
 * </ul>
 */
class InboundTranslationFailureProperties {

    /** Small configured translation budget so the timeout path stays fast in tests. */
    private static final long TIMEOUT_MS = 200;

    /** How the simulated Translation_Provider fails for an inbound Declared_Intent. */
    enum FailureMode {
        /** Provider returns an error / empty result &rarr; {@code PROVIDER_ERROR} (Req 3.4). */
        ERROR,
        /** Provider blocks beyond the budget &rarr; {@code PROVIDER_TIMEOUT} (Req 3.3). */
        TIMEOUT
    }

    // Feature: indian-language-translation, Property 8: Inbound translation failure rejects the submission with no session
    @Property(tries = 200)
    void inboundTranslationFailureRejectsSubmissionWithNoSession(
            @ForAll("sourceTexts") String sourceText,
            @ForAll("nonEnglishTags") LanguageTag sourceTag,
            @ForAll("operatorIds") String operatorId,
            @ForAll FailureMode mode) {

        InMemoryIntentSessionRepository sessions = new InMemoryIntentSessionRepository();
        IntentSessionManager sessionManager =
                new DefaultIntentSessionManager(sessions, mock(AuditHistoryRepository.class));

        // The rejection path reads the operator's Language_Preference for the localized prompt.
        LanguagePreferenceService languagePreferenceService = mock(LanguagePreferenceService.class);
        when(languagePreferenceService.getPreference(operatorId)).thenReturn(sourceTag);

        InboundIntentService service = new InboundIntentService(
                failingTranslationService(mode), sessionManager, languagePreferenceService);

        InboundIntentResult result =
                service.submit(operatorId, sourceText, sourceTag, Actor.human(operatorId));

        // Req 3.3, 3.4: the submission is rejected — no session was opened.
        assertThat(result.status()).isEqualTo(InboundIntentResult.Status.REJECTED);
        assertThat(result.opened()).isFalse();
        assertThat(result.openedSession()).isEmpty();

        // Req 3.4: a localized retry/English prompt is presented in the Operator's Language_Preference.
        assertThat(result.messageText()).isPresent();
        assertThat(result.messageText().orElseThrow()).isNotBlank();

        // Req 3.3, 3.4: absolutely no Intent_Session was opened for the operator — neither queryable
        // as active nor persisted in the session repository.
        assertThat(sessionManager.activeSessionFor(operatorId)).isEmpty();
        assertThat(sessions.count()).isZero();
    }

    // ---- Worked examples --------------------------------------------------------------------------

    @Example
    void providerErrorRejectsInboundIntentWithNoSession() {
        InMemoryIntentSessionRepository sessions = new InMemoryIntentSessionRepository();
        IntentSessionManager sessionManager =
                new DefaultIntentSessionManager(sessions, mock(AuditHistoryRepository.class));
        LanguagePreferenceService prefs = mock(LanguagePreferenceService.class);
        when(prefs.getPreference("operator-1")).thenReturn(LanguageTag.of("hi"));

        InboundIntentService service = new InboundIntentService(
                failingTranslationService(FailureMode.ERROR), sessionManager, prefs);

        InboundIntentResult result = service.submit(
                "operator-1", "kripya review session-42 score 0.91", LanguageTag.of("hi"),
                Actor.human("operator-1"));

        assertThat(result.status()).isEqualTo(InboundIntentResult.Status.REJECTED);
        assertThat(result.openedSession()).isEmpty();
        assertThat(result.messageText()).isPresent();
        assertThat(result.messageText().orElseThrow()).isNotBlank();
        assertThat(sessionManager.activeSessionFor("operator-1")).isEmpty();
        assertThat(sessions.count()).isZero();
    }

    @Example
    void providerTimeoutRejectsInboundIntentWithNoSession() {
        InMemoryIntentSessionRepository sessions = new InMemoryIntentSessionRepository();
        IntentSessionManager sessionManager =
                new DefaultIntentSessionManager(sessions, mock(AuditHistoryRepository.class));
        LanguagePreferenceService prefs = mock(LanguagePreferenceService.class);
        when(prefs.getPreference("alice")).thenReturn(LanguageTag.of("bn"));

        InboundIntentService service = new InboundIntentService(
                failingTranslationService(FailureMode.TIMEOUT), sessionManager, prefs);

        InboundIntentResult result = service.submit(
                "alice", "run backup on host db.prod.internal", LanguageTag.of("bn"),
                Actor.human("alice"));

        assertThat(result.status()).isEqualTo(InboundIntentResult.Status.REJECTED);
        assertThat(result.openedSession()).isEmpty();
        assertThat(result.messageText()).isPresent();
        assertThat(result.messageText().orElseThrow()).isNotBlank();
        assertThat(sessionManager.activeSessionFor("alice")).isEmpty();
        assertThat(sessions.count()).isZero();
    }

    // ---- Wiring ----------------------------------------------------------------------------------

    /**
     * Builds a live {@link DefaultTranslationService} whose active provider always fails in the
     * requested mode. The api key and matching provider id are set so the text-translation capability
     * is enabled at startup — the resulting {@code PROVIDER_ERROR} / {@code PROVIDER_TIMEOUT} outcome
     * is a genuine provider failure rather than the capability gate.
     */
    private TranslationService failingTranslationService(FailureMode mode) {
        TranslationProvider provider = mode == FailureMode.ERROR
                ? new ErrorTranslationProvider()
                // Delay far beyond the small configured budget so the bounded call always times out.
                : new TimeoutTranslationProvider(Duration.ofMillis(TIMEOUT_MS * 25));

        TranslationProperties translationProperties = new TranslationProperties();
        translationProperties.setProvider(provider.id());
        translationProperties.setApiKey("test-key");
        translationProperties.setTimeoutMs(TIMEOUT_MS);

        TranslationRuntimeConfig runtimeConfig =
                new TranslationRuntimeConfig(translationProperties, new SpeechProperties());
        runtimeConfig.initialize();

        return new DefaultTranslationService(
                List.of(provider),
                new TranslationCache(),
                runtimeConfig,
                SupportedLanguages.defaults(),
                new TechnicalTokenProtector());
    }

    // ---- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> sourceTexts() {
        // Non-empty Declared_Intent content mixing plain prose with Technical_Tokens; the failing
        // provider never returns a usable translation regardless of content.
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

    // ---- In-memory repository fake ---------------------------------------------------------------

    /**
     * Deterministic, DB-free {@link IntentSessionRepository} backed by a map keyed on
     * {@code sessionId}. Overrides every method the manager uses so no live Mongo collection is
     * touched; the superclass constructor is satisfied with a mock {@link MongoDatabase} whose
     * {@code getCollection} return value is never used. Because the rejection path never opens a
     * session, {@link #count()} stays at zero.
     */
    private static final class InMemoryIntentSessionRepository extends IntentSessionRepository {

        private final Map<String, IntentSessionDocument> bySessionId = new HashMap<>();

        InMemoryIntentSessionRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(IntentSessionDocument session) {
            bySessionId.put(session.getSessionId(), session);
        }

        @Override
        public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
            return Optional.ofNullable(bySessionId.get(sessionId));
        }

        @Override
        public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
            return bySessionId.values().stream()
                    .filter(doc -> userId.equals(doc.getUserId()) && doc.isOpen())
                    .findFirst();
        }

        /** Number of saved session documents — expected to be zero when a submission is rejected. */
        int count() {
            return bySessionId.size();
        }
    }
}
