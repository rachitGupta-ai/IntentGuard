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
 * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first,
 * and deterministic.
 *
 * <p>Property-based tests for
 * {@link DefaultUserProfileService#assembleCommandTimeline(String, ActiveWindow)} exercised via
 * the package-private method directly. Repositories are in-memory fakes — no MongoDB required.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 2.1
 */
class CommandTimelinePropertiesTest {

    private static final String TARGET_USER = "target-user";

    private FakeAuditHistoryRepository auditRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        auditRepo = new FakeAuditHistoryRepository();
        service = new DefaultUserProfileService(
                auditRepo,
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());
    }

    // -------------------------------------------------------------------------
    // P3a: All returned entries are within [window.start, window.end]
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded,
     * oldest-first, and deterministic.
     *
     * <p>For any non-empty set of audit documents stored for the target user, all entries returned
     * by {@code assembleCommandTimeline} have timestamps within [window.start, window.end]
     * inclusive (Validates: Requirements 2.1).
     */
    @Property(tries = 100)
    void timelineEntriesAreWindowBounded(@ForAll("windowedAuditDocs") WindowedAuditScenario scenario) {
        // Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first, and deterministic

        auditRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        CategoryView<CommandDecisionEntry> view =
                service.assembleCommandTimeline(TARGET_USER, scenario.window());

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        for (CommandDecisionEntry entry : view.records()) {
            assertThat(entry.timestamp())
                    .as("entry timestamp must be >= window.start (%d)", scenario.window().start())
                    .isGreaterThanOrEqualTo(scenario.window().start());
            assertThat(entry.timestamp())
                    .as("entry timestamp must be <= window.end (%d)", scenario.window().end())
                    .isLessThanOrEqualTo(scenario.window().end());
        }
    }

    // -------------------------------------------------------------------------
    // P3b: Entries are ordered oldest-first by (timestamp asc, eventId asc)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded,
     * oldest-first, and deterministic.
     *
     * <p>Entries are ordered oldest-first: each entry's (timestamp, eventId) pair is
     * lexicographically ≤ the next (Validates: Requirements 2.1).
     */
    @Property(tries = 100)
    void timelineEntriesAreOldestFirst(@ForAll("windowedAuditDocs") WindowedAuditScenario scenario) {
        // Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first, and deterministic

        auditRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        List<CommandDecisionEntry> entries =
                service.assembleCommandTimeline(TARGET_USER, scenario.window()).records();

        for (int i = 0; i < entries.size() - 1; i++) {
            CommandDecisionEntry a = entries.get(i);
            CommandDecisionEntry b = entries.get(i + 1);

            if (a.timestamp() == b.timestamp()) {
                assertThat(a.eventId().compareTo(b.eventId()))
                        .as("tie on timestamp=%d: eventId '%s' must precede '%s'",
                                a.timestamp(), a.eventId(), b.eventId())
                        .isLessThanOrEqualTo(0);
            } else {
                assertThat(a.timestamp())
                        .as("timestamp %d must precede %d", a.timestamp(), b.timestamp())
                        .isLessThan(b.timestamp());
            }
        }
    }

    // -------------------------------------------------------------------------
    // P3c: Determinism — same inputs produce identical output on repeated calls
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded,
     * oldest-first, and deterministic.
     *
     * <p>Calling {@code assembleCommandTimeline} twice with identical inputs produces identical
     * output — same records in the same order (Validates: Requirements 2.1).
     */
    @Property(tries = 100)
    void timelineIsDeterministic(@ForAll("windowedAuditDocs") WindowedAuditScenario scenario) {
        // Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first, and deterministic

        auditRepo.setDocumentsForUser(TARGET_USER, scenario.docs());

        List<CommandDecisionEntry> first =
                service.assembleCommandTimeline(TARGET_USER, scenario.window()).records();
        List<CommandDecisionEntry> second =
                service.assembleCommandTimeline(TARGET_USER, scenario.window()).records();

        assertThat(first).as("repeated call must return identical results").isEqualTo(second);
    }

    // -------------------------------------------------------------------------
    // P3d: Documents for other users are not included
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded,
     * oldest-first, and deterministic.
     *
     * <p>Documents stored for a different user are never returned in the target user's timeline
     * (Validates: Requirements 2.1).
     */
    @Property(tries = 100)
    void timelineExcludesOtherUsers(@ForAll("windowedAuditDocs") WindowedAuditScenario scenario) {
        // Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first, and deterministic

        // Store the generated docs under a different user; target user has no docs.
        auditRepo.setDocumentsForUser("other-user", scenario.docs());

        CategoryView<CommandDecisionEntry> view =
                service.assembleCommandTimeline(TARGET_USER, scenario.window());

        assertThat(view.records())
                .as("timeline for target-user must be empty when no docs exist for that user")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P3e: Empty window produces empty result
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 3: Command timeline is window-bounded,
     * oldest-first, and deterministic.
     *
     * <p>When the window is the empty sentinel, the timeline is always empty regardless of stored
     * documents (Validates: Requirements 2.1, 7.5).
     */
    @Property(tries = 100)
    void timelineIsEmptyForEmptyWindow(@ForAll("auditDocLists") List<AuditHistoryDocument> docs) {
        // Feature: user-profiling-screen, Property 3: Command timeline is window-bounded, oldest-first, and deterministic

        auditRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<CommandDecisionEntry> view =
                service.assembleCommandTimeline(TARGET_USER, ActiveWindow.emptyWindow());

        assertThat(view.records())
                .as("empty window must always produce empty timeline")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    /**
     * Generates a window and a list of audit documents where a subset of documents fall within the
     * window (to make the bounded assertion meaningful).
     */
    @Provide
    Arbitrary<WindowedAuditScenario> windowedAuditDocs() {
        // Window: start in a range that allows docs before and after
        Arbitrary<Long> windowStart = Arbitraries.longs().between(1_000L, 1_900_000_000_000L);
        Arbitrary<Long> windowLength = Arbitraries.longs().between(1L, 86_400_000L);

        return Combinators.combine(windowStart, windowLength)
                .flatAs((start, length) -> {
                    long end = start + length;
                    ActiveWindow window = ActiveWindow.of(start, end);

                    // Generate docs: mix of in-window and out-of-window
                    Arbitrary<AuditHistoryDocument> inWindow = auditDoc(start, end);
                    Arbitrary<AuditHistoryDocument> outWindow = Arbitraries.oneOf(
                            auditDoc(0L, Math.max(0L, start - 1)),
                            auditDoc(end + 1, end + 86_400_000L));

                    Arbitrary<List<AuditHistoryDocument>> inWindowDocs =
                            inWindow.list().ofMinSize(0).ofMaxSize(10);
                    Arbitrary<List<AuditHistoryDocument>> outWindowDocs =
                            outWindow.list().ofMinSize(0).ofMaxSize(5);

                    return Combinators.combine(inWindowDocs, outWindowDocs).as((inList, outList) -> {
                        List<AuditHistoryDocument> all = new ArrayList<>(inList);
                        all.addAll(outList);
                        return new WindowedAuditScenario(window, all);
                    });
                });
    }

    /** Generates a flat list of AuditHistoryDocuments across a broad timestamp range. */
    @Provide
    Arbitrary<List<AuditHistoryDocument>> auditDocLists() {
        return auditDoc(1_000L, 2_000_000_000_000L).list().ofMinSize(0).ofMaxSize(15);
    }

    /** Generates a single AuditHistoryDocument for the TARGET_USER with timestamp in [tsMin, tsMax]. */
    private static Arbitrary<AuditHistoryDocument> auditDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(
                Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> id = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(12);
        Arbitrary<String> cmd = Arbitraries.strings().withChars("abcdefghijklmnop -/.")
                .ofMinLength(2).ofMaxLength(30);
        return Combinators.combine(ts, id, cmd).as((t, eid, c) -> {
            AuditHistoryDocument doc = new AuditHistoryDocument();
            doc.setUserId(TARGET_USER);
            doc.setEventId(eid);
            doc.setCommandText(c);
            doc.setTimestamp(t);
            doc.setCorrectiveAction("ALLOW");
            doc.setDivergenceScore(0.1);
            return doc;
        });
    }

    // -------------------------------------------------------------------------
    // Value type for scenario
    // -------------------------------------------------------------------------

    record WindowedAuditScenario(ActiveWindow window, List<AuditHistoryDocument> docs) {}

    // -------------------------------------------------------------------------
    // Fake / no-op repositories
    // -------------------------------------------------------------------------

    /**
     * In-memory fake for {@link AuditHistoryRepository}. Overrides only the methods invoked by
     * {@link DefaultUserProfileService#assembleCommandTimeline}; all other methods throw
     * {@link UnsupportedOperationException} to detect accidental writes.
     */
    static final class FakeAuditHistoryRepository extends AuditHistoryRepository {

        private String storedUserId;
        private List<AuditHistoryDocument> storedDocs = List.of();

        FakeAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        void setDocumentsForUser(String userId, List<AuditHistoryDocument> docs) {
            this.storedUserId = userId;
            this.storedDocs = new ArrayList<>(docs);
        }

        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long fromMs, long toMs) {
            if (!userId.equals(storedUserId)) {
                return List.of();
            }
            // Filter and return in insertion order (oldest-first is handled by the service)
            List<AuditHistoryDocument> result = new ArrayList<>();
            for (AuditHistoryDocument doc : storedDocs) {
                if (doc.getTimestamp() >= fromMs && doc.getTimestamp() <= toMs) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public void save(AuditHistoryDocument record) {
            throw new UnsupportedOperationException("FakeAuditHistoryRepository is read-only");
        }

        @Override
        public List<String> distinctUserIds() {
            return storedUserId != null ? List.of(storedUserId) : List.of();
        }

        @Override
        public Optional<Long> earliestTimestampForUser(String userId) {
            return Optional.empty();
        }
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
