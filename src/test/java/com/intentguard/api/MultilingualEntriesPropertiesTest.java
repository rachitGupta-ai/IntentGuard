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
import com.intentguard.translation.TranslationRecord;
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
 * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
 * non-English, window-bounded, and newest-first.
 *
 * <p>Property-based tests for
 * {@link DefaultUserProfileService#assembleMultilingual(String, ActiveWindow)} exercised
 * via the package-private method directly. Repositories are in-memory fakes — no MongoDB.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 3.1, 3.4
 */
class MultilingualEntriesPropertiesTest {

    private static final String TARGET_USER = "target-user";

    /** Non-English supported language tags. */
    private static final List<String> NON_ENGLISH_TAGS =
            List.of("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa", "or");

    private FakeIntentSessionRepository sessionRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        sessionRepo = new FakeIntentSessionRepository();
        service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                sessionRepo,
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());
    }

    // -------------------------------------------------------------------------
    // P4a: All returned entries are within [window.start, window.end]
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>Every multilingual entry's timestamp lies within [window.start, window.end] inclusive
     * (Validates: Requirements 3.1).
     */
    @Property(tries = 100)
    void multilingualEntriesAreWindowBounded(
            @ForAll("windowedSessionScenarios") WindowedSessionScenario scenario) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        sessionRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        CategoryView<MultilingualEntryView> view =
                service.assembleMultilingual(TARGET_USER, scenario.window());

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        for (MultilingualEntryView entry : view.records()) {
            assertThat(entry.timestamp())
                    .as("timestamp must be >= window.start (%d)", scenario.window().start())
                    .isGreaterThanOrEqualTo(scenario.window().start());
            assertThat(entry.timestamp())
                    .as("timestamp must be <= window.end (%d)", scenario.window().end())
                    .isLessThanOrEqualTo(scenario.window().end());
        }
    }

    // -------------------------------------------------------------------------
    // P4b: Entries are ordered newest-first by (timestamp desc, sessionId desc)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>Entries are ordered newest-first: each entry's timestamp is ≥ the next, and ties are
     * broken by sessionId descending (Validates: Requirements 3.1).
     */
    @Property(tries = 100)
    void multilingualEntriesAreNewestFirst(
            @ForAll("windowedSessionScenarios") WindowedSessionScenario scenario) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        sessionRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        List<MultilingualEntryView> entries =
                service.assembleMultilingual(TARGET_USER, scenario.window()).records();

        for (int i = 0; i < entries.size() - 1; i++) {
            MultilingualEntryView a = entries.get(i);
            MultilingualEntryView b = entries.get(i + 1);

            if (a.timestamp() == b.timestamp()) {
                assertThat(a.sessionId().compareTo(b.sessionId()))
                        .as("tie on timestamp=%d: sessionId '%s' must be >= '%s' (desc)",
                                a.timestamp(), a.sessionId(), b.sessionId())
                        .isGreaterThanOrEqualTo(0);
            } else {
                assertThat(a.timestamp())
                        .as("timestamp %d must be >= %d (newest-first)", a.timestamp(), b.timestamp())
                        .isGreaterThan(b.timestamp());
            }
        }
    }

    // -------------------------------------------------------------------------
    // P4c: English-only sessions are never returned
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>No entry in the result has a source language of "en" — English sessions are excluded
     * (Validates: Requirements 3.1, 3.4).
     */
    @Property(tries = 100)
    void multilingualEntriesAreNonEnglish(
            @ForAll("windowedSessionScenarios") WindowedSessionScenario scenario) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        sessionRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        List<MultilingualEntryView> entries =
                service.assembleMultilingual(TARGET_USER, scenario.window()).records();

        for (MultilingualEntryView entry : entries) {
            assertThat(entry.sourceLanguageTag())
                    .as("multilingual entry must never have sourceLanguageTag 'en'")
                    .isNotEqualToIgnoringCase("en");
        }
    }

    // -------------------------------------------------------------------------
    // P4d: Sessions for other users are never included
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>Sessions stored under a different userId are not returned for the target user
     * (Validates: Requirements 3.1, 3.4).
     */
    @Property(tries = 100)
    void multilingualEntriesExcludeOtherUsers(
            @ForAll("windowedSessionScenarios") WindowedSessionScenario scenario) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        sessionRepo.setDocumentsForUser("other-user", scenario.docs());

        CategoryView<MultilingualEntryView> view =
                service.assembleMultilingual(TARGET_USER, scenario.window());

        assertThat(view.records())
                .as("multilingual entries for target-user must be empty when all sessions belong to another user")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P4e: Empty window produces empty result
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>When the window is the empty sentinel, the result is always empty regardless of stored
     * sessions (Validates: Requirements 3.1, 7.5).
     */
    @Property(tries = 100)
    void multilingualEntriesEmptyForEmptyWindow(
            @ForAll("sessionDocLists") List<IntentSessionDocument> docs) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        sessionRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<MultilingualEntryView> view =
                service.assembleMultilingual(TARGET_USER, ActiveWindow.emptyWindow());

        assertThat(view.records())
                .as("empty window must always produce empty multilingual list")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P4f: Non-attributable sessions (blank userId) are excluded
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 4: Multilingual entries are attributable,
     * non-English, window-bounded, and newest-first.
     *
     * <p>Sessions with a blank userId are never returned — only attributable sessions qualify
     * (Validates: Requirements 3.4).
     */
    @Property(tries = 100)
    void nonAttributableSessionsAreExcluded(
            @ForAll("windowedSessionScenarios") WindowedSessionScenario scenario) {
        // Feature: user-profiling-screen, Property 4: Multilingual entries are attributable, non-English, window-bounded, and newest-first

        // Blank out userId on all stored docs for the target user.
        List<IntentSessionDocument> blanked = new ArrayList<>();
        for (IntentSessionDocument doc : scenario.docs()) {
            IntentSessionDocument copy = copyDoc(doc);
            copy.setUserId("");
            blanked.add(copy);
        }
        sessionRepo.setDocumentsForUser(TARGET_USER, blanked);

        CategoryView<MultilingualEntryView> view =
                service.assembleMultilingual(TARGET_USER, scenario.window());

        assertThat(view.records())
                .as("sessions with blank userId must all be excluded")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    /** Generates a window paired with a mix of in-window and out-of-window session documents. */
    @Provide
    Arbitrary<WindowedSessionScenario> windowedSessionScenarios() {
        Arbitrary<Long> windowStart = Arbitraries.longs().between(1_000L, 1_900_000_000_000L);
        Arbitrary<Long> windowLength = Arbitraries.longs().between(1L, 86_400_000L);

        return Combinators.combine(windowStart, windowLength)
                .flatAs((start, length) -> {
                    long end = start + length;
                    ActiveWindow window = ActiveWindow.of(start, end);

                    Arbitrary<IntentSessionDocument> inWindow = sessionDoc(start, end);
                    Arbitrary<IntentSessionDocument> outWindow = Arbitraries.oneOf(
                            sessionDoc(0L, Math.max(0L, start - 1)),
                            sessionDoc(end + 1, end + 86_400_000L));

                    Arbitrary<List<IntentSessionDocument>> inDocs = inWindow.list().ofMinSize(0).ofMaxSize(10);
                    Arbitrary<List<IntentSessionDocument>> outDocs = outWindow.list().ofMinSize(0).ofMaxSize(5);

                    return Combinators.combine(inDocs, outDocs).as((in, out) -> {
                        List<IntentSessionDocument> all = new ArrayList<>(in);
                        all.addAll(out);
                        return new WindowedSessionScenario(window, all);
                    });
                });
    }

    /** Generates a flat list of session documents for the empty-window test. */
    @Provide
    Arbitrary<List<IntentSessionDocument>> sessionDocLists() {
        return sessionDoc(1_000L, 2_000_000_000_000L).list().ofMinSize(0).ofMaxSize(15);
    }

    /** Generates a single IntentSessionDocument for TARGET_USER with a non-English tag. */
    private static Arbitrary<IntentSessionDocument> sessionDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(
                Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> sid = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(12);
        Arbitrary<String> intent = Arbitraries.strings()
                .withChars("abcdefghijklmnop /.-_")
                .ofMinLength(3).ofMaxLength(30);
        Arbitrary<String> tag = Arbitraries.of(NON_ENGLISH_TAGS.toArray(new String[0]));
        // englishText: sometimes present, sometimes null/blank
        Arbitrary<String> englishText = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30),
                Arbitraries.just(null),
                Arbitraries.just(""));

        return Combinators.combine(ts, sid, intent, tag, englishText)
                .as((t, s, i, tg, eng) -> {
                    IntentSessionDocument doc = new IntentSessionDocument();
                    doc.setUserId(TARGET_USER);
                    doc.setSessionId(s);
                    doc.setOriginalDeclaredIntent(i);
                    doc.setDeclaredIntentLanguageTag(tg);
                    doc.setDeclaredIntent(eng);
                    doc.setStartedAt(t);
                    return doc;
                });
    }

    private static IntentSessionDocument copyDoc(IntentSessionDocument src) {
        IntentSessionDocument copy = new IntentSessionDocument();
        copy.setUserId(src.getUserId());
        copy.setSessionId(src.getSessionId());
        copy.setOriginalDeclaredIntent(src.getOriginalDeclaredIntent());
        copy.setDeclaredIntentLanguageTag(src.getDeclaredIntentLanguageTag());
        copy.setDeclaredIntent(src.getDeclaredIntent());
        copy.setStartedAt(src.getStartedAt());
        return copy;
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    record WindowedSessionScenario(ActiveWindow window, List<IntentSessionDocument> docs) {}

    // -------------------------------------------------------------------------
    // Fake / no-op repositories
    // -------------------------------------------------------------------------

    static final class FakeIntentSessionRepository extends IntentSessionRepository {
        private String storedUserId;
        private List<IntentSessionDocument> storedDocs = List.of();

        FakeIntentSessionRepository() { super(mock(MongoDatabase.class)); }

        void setDocumentsForUser(String userId, List<IntentSessionDocument> docs) {
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

    static final class NoOpTranslationRecordRepository extends TranslationRecordRepository {
        NoOpTranslationRecordRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<TranslationRecord> findByTimeRange(long from, long to) { return List.of(); }
    }
}
