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
 * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
 * window-bounded, ordered, and capped.
 *
 * <p>Property-based tests for
 * {@link DefaultUserProfileService#assembleAssistQueries(String, ActiveWindow)} exercised
 * via the package-private method directly. Repositories are in-memory fakes — no MongoDB.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 4.1
 */
class AssistQueriesPropertiesTest {

    private static final String TARGET_OPERATOR = "operator-1";

    private FakeAssistAuditRepository assistRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        assistRepo = new FakeAssistAuditRepository();
        service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                assistRepo,
                new NoOpTranslationRecordRepository());
    }

    // -------------------------------------------------------------------------
    // P6a: All returned entries are within [window.start, window.end]
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
     * window-bounded, ordered, and capped.
     *
     * <p>Every assist query entry's timestamp lies within [window.start, window.end] inclusive
     * (Validates: Requirements 4.1).
     */
    @Property(tries = 100)
    void assistQueriesAreWindowBounded(
            @ForAll("windowedAssistScenarios") WindowedAssistScenario scenario) {
        // Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed, window-bounded, ordered, and capped

        assistRepo.setDocumentsForUser(TARGET_OPERATOR, scenario.docs());

        CategoryView<AssistQueryView> view =
                service.assembleAssistQueries(TARGET_OPERATOR, scenario.window());

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        for (AssistQueryView entry : view.records()) {
            assertThat(entry.timestamp())
                    .as("timestamp must be >= window.start (%d)", scenario.window().start())
                    .isGreaterThanOrEqualTo(scenario.window().start());
            assertThat(entry.timestamp())
                    .as("timestamp must be <= window.end (%d)", scenario.window().end())
                    .isLessThanOrEqualTo(scenario.window().end());
        }
    }

    // -------------------------------------------------------------------------
    // P6b: Entries are ordered oldest-first by (timestamp asc, id asc)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
     * window-bounded, ordered, and capped.
     *
     * <p>Entries are ordered oldest-first: each entry's (timestamp, id) pair is lexicographically
     * ≤ the next (Validates: Requirements 4.1).
     */
    @Property(tries = 100)
    void assistQueriesAreOldestFirst(
            @ForAll("windowedAssistScenarios") WindowedAssistScenario scenario) {
        // Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed, window-bounded, ordered, and capped

        assistRepo.setDocumentsForUser(TARGET_OPERATOR, scenario.docs());

        List<AssistQueryView> entries =
                service.assembleAssistQueries(TARGET_OPERATOR, scenario.window()).records();

        for (int i = 0; i < entries.size() - 1; i++) {
            AssistQueryView a = entries.get(i);
            AssistQueryView b = entries.get(i + 1);

            if (a.timestamp() == b.timestamp()) {
                assertThat(a.id().compareTo(b.id()))
                        .as("tie on timestamp=%d: id '%s' must precede '%s' (asc)",
                                a.timestamp(), a.id(), b.id())
                        .isLessThanOrEqualTo(0);
            } else {
                assertThat(a.timestamp())
                        .as("timestamp %d must be <= %d (oldest-first)", a.timestamp(), b.timestamp())
                        .isLessThan(b.timestamp());
            }
        }
    }

    // -------------------------------------------------------------------------
    // P6c: Operator-attribution — other operators' queries are excluded
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
     * window-bounded, ordered, and capped.
     *
     * <p>Documents stored for a different operator are never returned for the target operator
     * (Validates: Requirements 4.1).
     */
    @Property(tries = 100)
    void assistQueriesExcludeOtherOperators(
            @ForAll("windowedAssistScenarios") WindowedAssistScenario scenario) {
        // Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed, window-bounded, ordered, and capped

        assistRepo.setDocumentsForUser("other-operator", scenario.docs());

        CategoryView<AssistQueryView> view =
                service.assembleAssistQueries(TARGET_OPERATOR, scenario.window());

        assertThat(view.records())
                .as("queries for target operator must be empty when all docs belong to a different operator")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P6d: Determinism — same inputs produce identical output
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
     * window-bounded, ordered, and capped.
     *
     * <p>Calling {@code assembleAssistQueries} twice with identical inputs produces identical
     * output — same records in the same order (Validates: Requirements 4.1).
     */
    @Property(tries = 100)
    void assistQueriesAreDeterministic(
            @ForAll("windowedAssistScenarios") WindowedAssistScenario scenario) {
        // Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed, window-bounded, ordered, and capped

        assistRepo.setDocumentsForUser(TARGET_OPERATOR, scenario.docs());

        List<AssistQueryView> first =
                service.assembleAssistQueries(TARGET_OPERATOR, scenario.window()).records();
        List<AssistQueryView> second =
                service.assembleAssistQueries(TARGET_OPERATOR, scenario.window()).records();

        assertThat(first).as("repeated call must produce identical results").isEqualTo(second);
    }

    // -------------------------------------------------------------------------
    // P6e: Empty window produces empty result
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed,
     * window-bounded, ordered, and capped.
     *
     * <p>When the window is the empty sentinel, the result is always empty regardless of stored
     * documents (Validates: Requirements 4.1, 7.5).
     */
    @Property(tries = 100)
    void assistQueriesEmptyForEmptyWindow(
            @ForAll("assistDocLists") List<AssistAuditDocument> docs) {
        // Feature: user-profiling-screen, Property 6: Assistant queries are operator-attributed, window-bounded, ordered, and capped

        assistRepo.setDocumentsForUser(TARGET_OPERATOR, docs);

        CategoryView<AssistQueryView> view =
                service.assembleAssistQueries(TARGET_OPERATOR, ActiveWindow.emptyWindow());

        assertThat(view.records())
                .as("empty window must always produce empty assist queries")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<WindowedAssistScenario> windowedAssistScenarios() {
        Arbitrary<Long> windowStart = Arbitraries.longs().between(1_000L, 1_900_000_000_000L);
        Arbitrary<Long> windowLength = Arbitraries.longs().between(1L, 86_400_000L);

        return Combinators.combine(windowStart, windowLength)
                .flatAs((start, length) -> {
                    long end = start + length;
                    ActiveWindow window = ActiveWindow.of(start, end);

                    Arbitrary<AssistAuditDocument> inWindow = assistDoc(start, end);
                    Arbitrary<AssistAuditDocument> outWindow = Arbitraries.oneOf(
                            assistDoc(0L, Math.max(0L, start - 1)),
                            assistDoc(end + 1, end + 86_400_000L));

                    Arbitrary<List<AssistAuditDocument>> inDocs = inWindow.list().ofMinSize(0).ofMaxSize(10);
                    Arbitrary<List<AssistAuditDocument>> outDocs = outWindow.list().ofMinSize(0).ofMaxSize(5);

                    return Combinators.combine(inDocs, outDocs).as((in, out) -> {
                        List<AssistAuditDocument> all = new ArrayList<>(in);
                        all.addAll(out);
                        return new WindowedAssistScenario(window, all);
                    });
                });
    }

    @Provide
    Arbitrary<List<AssistAuditDocument>> assistDocLists() {
        return assistDoc(1_000L, 2_000_000_000_000L).list().ofMinSize(0).ofMaxSize(15);
    }

    private static Arbitrary<AssistAuditDocument> assistDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(
                Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        // IDs must be lexicographically comparable hex-like strings
        Arbitrary<String> id = Arbitraries.strings()
                .withChars("0123456789abcdef")
                .ofMinLength(8).ofMaxLength(24);
        Arbitrary<String> query = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ")
                .ofMinLength(3).ofMaxLength(40);
        Arbitrary<List<String>> cmds = Arbitraries.strings().alpha()
                .ofMinLength(2).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(3);

        return Combinators.combine(ts, id, query, cmds).as((t, docId, q, c) -> {
            AssistAuditDocument doc = new AssistAuditDocument();
            doc.setId(docId);
            doc.setOperatorId(TARGET_OPERATOR);
            doc.setEventType("QUERY");
            doc.setQueryEnglish(q);
            doc.setGeneratedCommands(c);
            doc.setTimestamp(t);
            return doc;
        });
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    record WindowedAssistScenario(ActiveWindow window, List<AssistAuditDocument> docs) {}

    // -------------------------------------------------------------------------
    // Fake / no-op repositories
    // -------------------------------------------------------------------------

    static final class FakeAssistAuditRepository extends AssistAuditRepository {
        private String storedOperatorId;
        private List<AssistAuditDocument> storedDocs = List.of();

        FakeAssistAuditRepository() { super(mock(MongoDatabase.class)); }

        void setDocumentsForUser(String operatorId, List<AssistAuditDocument> docs) {
            this.storedOperatorId = operatorId;
            this.storedDocs = new ArrayList<>(docs);
        }

        @Override
        public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(
                String operatorId, long from, long to) {
            if (!operatorId.equals(storedOperatorId)) return List.of();
            List<AssistAuditDocument> result = new ArrayList<>();
            for (AssistAuditDocument doc : storedDocs) {
                if (doc.getTimestamp() >= from && doc.getTimestamp() <= to) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public List<String> distinctOperatorIds() {
            return storedOperatorId != null ? List.of(storedOperatorId) : List.of();
        }

        @Override
        public Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
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

    static final class NoOpIntentSessionRepository extends IntentSessionRepository {
        NoOpIntentSessionRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
            return List.of();
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }

        @Override
        public Optional<Long> earliestStartedAtForUser(String userId) { return Optional.empty(); }
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

    static final class NoOpTranslationRecordRepository extends TranslationRecordRepository {
        NoOpTranslationRecordRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<TranslationRecord> findByTimeRange(long from, long to) { return List.of(); }
    }
}
