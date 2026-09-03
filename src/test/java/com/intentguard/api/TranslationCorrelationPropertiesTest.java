package com.intentguard.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intentguard.assist.AssistAuditDocument;
import com.intentguard.assist.AssistAuditRepository;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.TranslationRecord;
import com.intentguard.translation.TranslationRecordKind;
import com.intentguard.translation.TranslationRecordRepository;
import com.mongodb.client.MongoDatabase;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;

/**
 * Feature: user-profiling-screen, Property 8: Translation records are attributable,
 * window-bounded, ordered, and deterministic.
 *
 * <p>Property-based tests for
 * {@link DefaultUserProfileService#assembleTranslations(String, ActiveWindow)} exercised
 * via the package-private method directly. Repositories are in-memory fakes — no MongoDB.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 5.1, 5.3
 */
class TranslationCorrelationPropertiesTest {

    private static final String TARGET_USER = "user-alpha";
    private static final String CORRELATION_INTENT = "check /etc/nginx/nginx.conf status";

    private FakeIntentSessionRepository sessionRepo;
    private FakeTranslationRecordRepository translationRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        sessionRepo = new FakeIntentSessionRepository();
        translationRepo = new FakeTranslationRecordRepository();
        service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                sessionRepo,
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                translationRepo);
    }

    // -------------------------------------------------------------------------
    // P8a: Only correlated records (matching user's originalDeclaredIntent) are returned
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>Only translation records whose {@code sourceText} matches at least one of the target
     * user's {@code originalDeclaredIntent} values within the window are returned (Req 5.3).
     *
     * <p>Validates: Requirements 5.1, 5.3
     */
    @Property(tries = 100)
    void onlyCorrelatedRecordsAreReturned(
            @ForAll("correlatedScenarios") CorrelatedScenario scenario) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, scenario.sessions());
        translationRepo.setRecordsForWindow(scenario.allTranslations());

        CategoryView<TranslationRecordView> view =
                service.assembleTranslations(TARGET_USER, scenario.window());

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);

        // Build correlation keys from sessions
        List<String> expectedSources = new ArrayList<>();
        for (IntentSessionDocument s : scenario.sessions()) {
            if (s.getOriginalDeclaredIntent() != null && !s.getOriginalDeclaredIntent().isBlank()
                    && s.getStartedAt() >= scenario.window().start()
                    && s.getStartedAt() <= scenario.window().end()) {
                expectedSources.add(s.getOriginalDeclaredIntent());
            }
        }

        for (TranslationRecordView entry : view.records()) {
            assertThat(expectedSources)
                    .as("sourceText '%s' must match one of the user's declared intents", entry.sourceText())
                    .contains(entry.sourceText());
        }
    }

    // -------------------------------------------------------------------------
    // P8b: Unattributed records (no matching intent) are excluded
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>When the user has no intent sessions in the window, all translation records are excluded
     * (Validates: Requirements 5.3).
     */
    @Property(tries = 100)
    void unattributedRecordsAreExcludedWhenNoUserSessions(
            @ForAll("translationRecordLists") List<TranslationRecord> records) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, List.of()); // no sessions
        translationRepo.setRecordsForWindow(records);

        ActiveWindow window = ActiveWindow.of(1_000_000L, 2_000_000_000_000L);
        CategoryView<TranslationRecordView> view =
                service.assembleTranslations(TARGET_USER, window);

        assertThat(view.records())
                .as("no user sessions → no correlated translations, result must be empty")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P8c: Entries are window-bounded
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>Every returned entry's timestamp lies within the active window (Validates: Req 5.1).
     */
    @Property(tries = 100)
    void translationEntriesAreWindowBounded(
            @ForAll("correlatedScenarios") CorrelatedScenario scenario) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, scenario.sessions());
        translationRepo.setRecordsForWindow(scenario.allTranslations());

        CategoryView<TranslationRecordView> view =
                service.assembleTranslations(TARGET_USER, scenario.window());

        for (TranslationRecordView entry : view.records()) {
            assertThat(entry.timestamp())
                    .as("timestamp must be >= window.start (%d)", scenario.window().start())
                    .isGreaterThanOrEqualTo(scenario.window().start());
            assertThat(entry.timestamp())
                    .as("timestamp must be <= window.end (%d)", scenario.window().end())
                    .isLessThanOrEqualTo(scenario.window().end());
        }
    }

    // -------------------------------------------------------------------------
    // P8d: Entries are ordered oldest-first by (timestamp asc, content-key asc)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>Entries are ordered oldest-first (Validates: Requirements 5.1).
     */
    @Property(tries = 100)
    void translationEntriesAreOldestFirst(
            @ForAll("correlatedScenarios") CorrelatedScenario scenario) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, scenario.sessions());
        translationRepo.setRecordsForWindow(scenario.allTranslations());

        List<TranslationRecordView> entries =
                service.assembleTranslations(TARGET_USER, scenario.window()).records();

        for (int i = 0; i < entries.size() - 1; i++) {
            assertThat(entries.get(i).timestamp())
                    .as("entry[%d].timestamp must be <= entry[%d].timestamp", i, i + 1)
                    .isLessThanOrEqualTo(entries.get(i + 1).timestamp());
        }
    }

    // -------------------------------------------------------------------------
    // P8e: Determinism — repeated calls with same inputs produce identical output
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>Calling {@code assembleTranslations} twice returns the same list (Validates: Req 5.1).
     */
    @Property(tries = 100)
    void translationsAreDeterministic(
            @ForAll("correlatedScenarios") CorrelatedScenario scenario) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, scenario.sessions());
        translationRepo.setRecordsForWindow(scenario.allTranslations());

        List<TranslationRecordView> first =
                service.assembleTranslations(TARGET_USER, scenario.window()).records();
        List<TranslationRecordView> second =
                service.assembleTranslations(TARGET_USER, scenario.window()).records();

        assertThat(first).as("repeated call must produce identical results").isEqualTo(second);
    }

    // -------------------------------------------------------------------------
    // P8f: degraded flag is set correctly (src==tgt OR translated==source)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 8: Translation records are attributable,
     * window-bounded, ordered, and deterministic.
     *
     * <p>For fallback-shaped records (src lang == tgt lang, or translatedText == sourceText),
     * the {@code degraded} flag must be {@code true}; for normal translations, it must be
     * {@code false} (Validates: Requirements 5.1).
     */
    @Property(tries = 100)
    void degradedFlagReflectsFallbackShape(
            @ForAll("fallbackScenarios") FallbackScenario scenario) {
        // Feature: user-profiling-screen, Property 8: Translation records are attributable, window-bounded, ordered, and deterministic

        sessionRepo.setForUser(TARGET_USER, scenario.sessions());
        translationRepo.setRecordsForWindow(scenario.translations());

        ActiveWindow window = scenario.window();
        CategoryView<TranslationRecordView> view =
                service.assembleTranslations(TARGET_USER, window);

        for (TranslationRecordView entry : view.records()) {
            boolean expectedDegraded = entry.sourceLanguageTag().equals(entry.targetLanguageTag())
                    || entry.translatedText().equals(entry.sourceText());
            assertThat(entry.degraded())
                    .as("degraded must be %b for src='%s', tgt='%s', srcText='%s', translated='%s'",
                            expectedDegraded, entry.sourceLanguageTag(), entry.targetLanguageTag(),
                            entry.sourceText(), entry.translatedText())
                    .isEqualTo(expectedDegraded);
        }
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<CorrelatedScenario> correlatedScenarios() {
        Arbitrary<Long> windowStart = Arbitraries.longs().between(1_000L, 1_000_000_000_000L);
        Arbitrary<Long> windowLength = Arbitraries.longs().between(1L, 86_400_000L);

        return Combinators.combine(windowStart, windowLength)
                .flatAs((start, length) -> {
                    long end = start + length;
                    ActiveWindow window = ActiveWindow.of(start, end);

                    // Sessions in the window with known intents
                    Arbitrary<String> intentText = Arbitraries.strings()
                            .withChars("abcdefghijklmnopqrstuvwxyz /.-_")
                            .ofMinLength(5).ofMaxLength(30);

                    Arbitrary<IntentSessionDocument> inWindowSession =
                            intentSessionDoc(start, end, intentText);

                    Arbitrary<List<IntentSessionDocument>> sessionList =
                            inWindowSession.list().ofMinSize(1).ofMaxSize(5);

                    return sessionList.flatMap(sessions -> {
                        // Build some matching and some non-matching translations
                        List<String> matchingIntents = new ArrayList<>();
                        for (IntentSessionDocument s : sessions) {
                            if (s.getOriginalDeclaredIntent() != null) {
                                matchingIntents.add(s.getOriginalDeclaredIntent());
                            }
                        }
                        String dummyIntent = matchingIntents.isEmpty() ? CORRELATION_INTENT : matchingIntents.get(0);

                        Arbitrary<TranslationRecord> matchingRec = translationRec(start, end, dummyIntent);
                        Arbitrary<TranslationRecord> nonMatchingRec = translationRec(start, end, "unrelated-text-xyz");

                        Arbitrary<List<TranslationRecord>> matching = matchingRec.list().ofMinSize(0).ofMaxSize(8);
                        Arbitrary<List<TranslationRecord>> nonMatching = nonMatchingRec.list().ofMinSize(0).ofMaxSize(3);

                        return Combinators.combine(matching, nonMatching).as((m, nm) -> {
                            List<TranslationRecord> all = new ArrayList<>(m);
                            all.addAll(nm);
                            return new CorrelatedScenario(window, sessions, all);
                        });
                    });
                });
    }

    @Provide
    Arbitrary<List<TranslationRecord>> translationRecordLists() {
        return translationRec(1_000L, 2_000_000_000_000L, "some intent")
                .list().ofMinSize(0).ofMaxSize(15);
    }

    @Provide
    Arbitrary<FallbackScenario> fallbackScenarios() {
        long start = 1_000_000L;
        long end = 2_000_000_000_000L;
        ActiveWindow window = ActiveWindow.of(start, end);

        // One session with a known intent to correlate translations to
        IntentSessionDocument session = new IntentSessionDocument();
        session.setUserId(TARGET_USER);
        session.setSessionId("fixed-session");
        session.setOriginalDeclaredIntent(CORRELATION_INTENT);
        session.setDeclaredIntentLanguageTag("hi");
        session.setStartedAt(start + 100L);

        // Generate translations: mix of fallback-shaped and normal
        Arbitrary<TranslationRecord> fallbackSameLang = translationRec(start, end, CORRELATION_INTENT)
                .map(r -> new TranslationRecord(r.sourceText(), r.translatedText(),
                        r.sourceLanguageTag(), r.sourceLanguageTag(), // same src==tgt
                        r.providerId(), r.kind(), r.timestamp()));

        Arbitrary<TranslationRecord> fallbackSameText = translationRec(start, end, CORRELATION_INTENT)
                .map(r -> new TranslationRecord(r.sourceText(), r.sourceText(), // translated==source
                        r.sourceLanguageTag(), r.targetLanguageTag(),
                        r.providerId(), r.kind(), r.timestamp()));

        Arbitrary<TranslationRecord> normalRec = translationRec(start, end, CORRELATION_INTENT)
                .filter(r -> !r.sourceLanguageTag().equals(r.targetLanguageTag())
                        && !r.translatedText().equals(r.sourceText()));

        Arbitrary<List<TranslationRecord>> recList = Arbitraries.oneOf(
                fallbackSameLang, fallbackSameText, normalRec).list().ofMinSize(0).ofMaxSize(10);

        return recList.map(recs -> new FallbackScenario(window, List.of(session), recs));
    }

    private static Arbitrary<IntentSessionDocument> intentSessionDoc(
            long tsMin, long tsMax, Arbitrary<String> intentArb) {
        Arbitrary<Long> ts = Arbitraries.longs().between(
                Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> sid = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(12);

        return Combinators.combine(ts, sid, intentArb).as((t, s, intent) -> {
            IntentSessionDocument doc = new IntentSessionDocument();
            doc.setUserId(TARGET_USER);
            doc.setSessionId(s);
            doc.setOriginalDeclaredIntent(intent);
            doc.setDeclaredIntentLanguageTag("hi");
            doc.setStartedAt(t);
            return doc;
        });
    }

    private static Arbitrary<TranslationRecord> translationRec(long tsMin, long tsMax, String sourceText) {
        Arbitrary<Long> ts = Arbitraries.longs().between(
                Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> translated = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30);
        Arbitrary<String> tgtLang = Arbitraries.of("en", "hi", "te");

        return Combinators.combine(ts, translated, tgtLang).as((t, tr, tgt) -> {
            // source lang != target lang for non-fallback records
            String srcLang = tgt.equals("en") ? "hi" : "en";
            return new TranslationRecord(
                    sourceText,
                    tr,
                    LanguageTag.of(srcLang),
                    LanguageTag.of(tgt),
                    "gemini",
                    TranslationRecordKind.INBOUND_INTENT,
                    t);
        });
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    record CorrelatedScenario(
            ActiveWindow window,
            List<IntentSessionDocument> sessions,
            List<TranslationRecord> allTranslations) {}

    record FallbackScenario(
            ActiveWindow window,
            List<IntentSessionDocument> sessions,
            List<TranslationRecord> translations) {}

    // -------------------------------------------------------------------------
    // Fake / no-op repositories
    // -------------------------------------------------------------------------

    static final class FakeIntentSessionRepository extends IntentSessionRepository {
        private String storedUserId;
        private List<IntentSessionDocument> storedDocs = List.of();

        FakeIntentSessionRepository() { super(mock(MongoDatabase.class)); }

        void setForUser(String userId, List<IntentSessionDocument> docs) {
            this.storedUserId = userId;
            this.storedDocs = new ArrayList<>(docs);
        }

        @Override
        public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
            if (!userId.equals(storedUserId)) return List.of();
            List<IntentSessionDocument> result = new ArrayList<>();
            for (IntentSessionDocument doc : storedDocs) {
                if (doc.getStartedAt() >= from && doc.getStartedAt() <= to) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public List<String> distinctUserIds() {
            return storedUserId != null ? List.of(storedUserId) : List.of();
        }

        @Override
        public Optional<Long> earliestStartedAtForUser(String userId) {
            return Optional.empty();
        }
    }

    static final class FakeTranslationRecordRepository extends TranslationRecordRepository {
        private List<TranslationRecord> storedRecords = List.of();

        FakeTranslationRecordRepository() { super(mock(MongoDatabase.class)); }

        void setRecordsForWindow(List<TranslationRecord> records) {
            this.storedRecords = new ArrayList<>(records);
        }

        @Override
        public List<TranslationRecord> findByTimeRange(long from, long to) {
            List<TranslationRecord> result = new ArrayList<>();
            for (TranslationRecord r : storedRecords) {
                if (r.timestamp() >= from && r.timestamp() <= to) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    static final class NoOpAuditHistoryRepository extends AuditHistoryRepository {
        NoOpAuditHistoryRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long from, long to) {
            return List.of();
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }

        @Override
        public Optional<Long> earliestTimestampForUser(String userId) { return Optional.empty(); }
    }

    static final class NoOpBehavioralProfileRepository extends BehavioralProfileRepository {
        NoOpBehavioralProfileRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.empty();
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }
    }

    static final class NoOpAssistAuditRepository extends AssistAuditRepository {
        NoOpAssistAuditRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(
                String operatorId, long from, long to) { return List.of(); }

        @Override
        public List<String> distinctOperatorIds() { return List.of(); }

        @Override
        public Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
            return Optional.empty();
        }
    }
}
