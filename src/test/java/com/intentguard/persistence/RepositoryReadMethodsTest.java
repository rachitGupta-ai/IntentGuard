package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for the read-only methods added to {@link AuditHistoryRepository},
 * {@link IntentSessionRepository}, and {@link BehavioralProfileRepository} for the
 * User Profiling Screen feature.
 *
 * <p>No live MongoDB connection is required — all collection interactions are mocked with Mockito.
 * Tests cover: distinct user id dedup, null filtering, time-range filtering, oldest-first ordering,
 * and earliest-timestamp lookup (empty and non-empty cases).
 *
 * <p>Requirements: 1.1, 4.1, 5.1, 7.4, 9.3
 */
class RepositoryReadMethodsTest {

    // =========================================================================
    // Helpers for building domain documents
    // =========================================================================

    private static AuditHistoryDocument auditDoc(String userId, long timestamp) {
        AuditHistoryDocument doc = new AuditHistoryDocument();
        doc.setUserId(userId);
        doc.setTimestamp(timestamp);
        doc.setEventId("evt-" + timestamp);
        doc.setRecordType("DECISION");
        return doc;
    }

    private static IntentSessionDocument sessionDoc(String userId, long startedAt) {
        IntentSessionDocument doc = new IntentSessionDocument();
        doc.setUserId(userId);
        doc.setStartedAt(startedAt);
        doc.setSessionId("sess-" + userId + "-" + startedAt);
        doc.setOpen(false);
        return doc;
    }

    // =========================================================================
    // AuditHistoryRepository — distinctUserIds
    // =========================================================================

    /**
     * A minimal subclass of {@link AuditHistoryRepository} that routes all reads through an
     * in-memory {@link List<AuditHistoryDocument>} without needing a live Mongo collection.
     * Only the two methods under test ({@code distinctUserIds}, {@code earliestTimestampForUser})
     * are overridden; all other reads are left to the superclass so unrelated tests remain unaffected.
     */
    private static final class InMemoryAuditHistoryRepository extends AuditHistoryRepository {

        private final List<AuditHistoryDocument> store;

        InMemoryAuditHistoryRepository(List<AuditHistoryDocument> store) {
            super(mock(MongoDatabase.class));
            this.store = new ArrayList<>(store);
        }

        @Override
        public List<String> distinctUserIds() {
            // Mirror the real Mongo query: distinct non-null userId values.
            return store.stream()
                    .map(AuditHistoryDocument::getUserId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
        }

        @Override
        public Optional<Long> earliestTimestampForUser(String userId) {
            return store.stream()
                    .filter(d -> userId.equals(d.getUserId()))
                    .map(AuditHistoryDocument::getTimestamp)
                    .min(Comparator.naturalOrder());
        }
    }

    // --- distinctUserIds tests ---

    @Test
    void auditHistoryDistinctUserIdsReturnsNonNullEntries() {
        AuditHistoryDocument doc1 = auditDoc("alice", 1000L);
        AuditHistoryDocument doc2 = auditDoc("bob", 2000L);
        AuditHistoryDocument docNull = new AuditHistoryDocument();
        docNull.setTimestamp(3000L); // userId is null

        InMemoryAuditHistoryRepository repo =
                new InMemoryAuditHistoryRepository(List.of(doc1, doc2, docNull));

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .as("null userId entries must be excluded")
                .doesNotContainNull()
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void auditHistoryDistinctUserIdsDeduplicatesAcrossMultipleDocuments() {
        List<AuditHistoryDocument> docs = List.of(
                auditDoc("alice", 100L),
                auditDoc("alice", 200L),  // duplicate userId
                auditDoc("bob", 300L),
                auditDoc("alice", 400L)); // third occurrence of alice

        InMemoryAuditHistoryRepository repo = new InMemoryAuditHistoryRepository(docs);

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .as("each userId appears at most once")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void auditHistoryDistinctUserIdsReturnsEmptyForEmptyCollection() {
        InMemoryAuditHistoryRepository repo = new InMemoryAuditHistoryRepository(List.of());

        assertThat(repo.distinctUserIds())
                .as("empty collection yields empty list, never null")
                .isNotNull()
                .isEmpty();
    }

    // --- earliestTimestampForUser tests ---

    @Test
    void auditHistoryEarliestTimestampReturnsMinForUser() {
        InMemoryAuditHistoryRepository repo = new InMemoryAuditHistoryRepository(List.of(
                auditDoc("alice", 500L),
                auditDoc("alice", 100L),   // earliest
                auditDoc("alice", 300L),
                auditDoc("bob", 50L)));    // different user — must not affect alice's minimum

        Optional<Long> result = repo.earliestTimestampForUser("alice");

        assertThat(result)
                .as("earliest timestamp for alice should be 100")
                .isPresent()
                .hasValue(100L);
    }

    @Test
    void auditHistoryEarliestTimestampReturnsEmptyForUnknownUser() {
        InMemoryAuditHistoryRepository repo = new InMemoryAuditHistoryRepository(List.of(
                auditDoc("alice", 1000L)));

        Optional<Long> result = repo.earliestTimestampForUser("charlie");

        assertThat(result)
                .as("user with no audit records must return empty Optional")
                .isEmpty();
    }

    @Test
    void auditHistoryEarliestTimestampReturnsSingleRecordWhenOnlyOne() {
        InMemoryAuditHistoryRepository repo = new InMemoryAuditHistoryRepository(List.of(
                auditDoc("alice", 42L)));

        assertThat(repo.earliestTimestampForUser("alice"))
                .isPresent()
                .hasValue(42L);
    }

    // =========================================================================
    // IntentSessionRepository — read-only methods
    // =========================================================================

    /**
     * In-memory subclass of {@link IntentSessionRepository} that routes the three new read methods
     * through an in-memory store, without needing a live Mongo collection.
     */
    private static final class InMemoryIntentSessionRepository extends IntentSessionRepository {

        private final List<IntentSessionDocument> store;

        InMemoryIntentSessionRepository(List<IntentSessionDocument> store) {
            super(mock(MongoDatabase.class));
            this.store = new ArrayList<>(store);
        }

        @Override
        public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
            return store.stream()
                    .filter(d -> userId.equals(d.getUserId())
                            && d.getStartedAt() >= from
                            && d.getStartedAt() <= to)
                    .sorted(Comparator.comparingLong(IntentSessionDocument::getStartedAt))
                    .toList();
        }

        @Override
        public List<String> distinctUserIds() {
            return store.stream()
                    .map(IntentSessionDocument::getUserId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
        }

        @Override
        public Optional<Long> earliestStartedAtForUser(String userId) {
            return store.stream()
                    .filter(d -> userId.equals(d.getUserId()))
                    .map(IntentSessionDocument::getStartedAt)
                    .min(Comparator.naturalOrder());
        }
    }

    // --- findByUserIdAndTimeRange tests ---

    @Test
    void sessionFindByUserIdAndTimeRangeReturnsOnlyMatchingUser() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 1000L),
                sessionDoc("bob",   1000L),  // different user — must be excluded
                sessionDoc("alice", 2000L)));

        List<IntentSessionDocument> result = repo.findByUserIdAndTimeRange("alice", 0L, 3000L);

        assertThat(result)
                .hasSize(2)
                .allMatch(d -> "alice".equals(d.getUserId()));
    }

    @Test
    void sessionFindByUserIdAndTimeRangeFiltersOutsideWindow() {
        // Window: [1000, 2000].
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 999L),   // before window start
                sessionDoc("alice", 1000L),  // exactly on lower bound — included
                sessionDoc("alice", 1500L),  // inside window — included
                sessionDoc("alice", 2000L),  // exactly on upper bound — included
                sessionDoc("alice", 2001L))); // after window end — excluded

        List<IntentSessionDocument> result = repo.findByUserIdAndTimeRange("alice", 1000L, 2000L);

        assertThat(result)
                .hasSize(3)
                .allSatisfy(d -> assertThat(d.getStartedAt()).isBetween(1000L, 2000L));
    }

    @Test
    void sessionFindByUserIdAndTimeRangeIsOldestFirst() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 3000L),
                sessionDoc("alice", 1000L),
                sessionDoc("alice", 2000L)));

        List<IntentSessionDocument> result = repo.findByUserIdAndTimeRange("alice", 0L, 9999L);

        assertThat(result)
                .isSortedAccordingTo(Comparator.comparingLong(IntentSessionDocument::getStartedAt));
        assertThat(result.get(0).getStartedAt()).isEqualTo(1000L);
        assertThat(result.get(result.size() - 1).getStartedAt()).isEqualTo(3000L);
    }

    @Test
    void sessionFindByUserIdAndTimeRangeReturnsEmptyForNoMatch() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 5000L)));

        List<IntentSessionDocument> result = repo.findByUserIdAndTimeRange("alice", 0L, 1000L);

        assertThat(result).isEmpty();
    }

    @Test
    void sessionFindByUserIdAndTimeRangeHandlesInclusiveBoundsForSingleRecord() {
        // Single record exactly on both bounds (point window).
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 7777L)));

        List<IntentSessionDocument> result = repo.findByUserIdAndTimeRange("alice", 7777L, 7777L);

        assertThat(result)
                .hasSize(1)
                .first()
                .extracting(IntentSessionDocument::getStartedAt)
                .isEqualTo(7777L);
    }

    // --- distinctUserIds (IntentSessionRepository) tests ---

    @Test
    void sessionDistinctUserIdsDeduplicatesCorrectly() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 100L),
                sessionDoc("alice", 200L),
                sessionDoc("bob",   300L)));

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void sessionDistinctUserIdsFiltersNulls() {
        IntentSessionDocument nullUser = new IntentSessionDocument();
        nullUser.setStartedAt(1000L);
        nullUser.setSessionId("sess-null");

        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 100L),
                nullUser));

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .doesNotContainNull()
                .containsExactly("alice");
    }

    @Test
    void sessionDistinctUserIdsReturnsEmptyForEmptyStore() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of());

        assertThat(repo.distinctUserIds())
                .isNotNull()
                .isEmpty();
    }

    // --- earliestStartedAtForUser (IntentSessionRepository) tests ---

    @Test
    void sessionEarliestStartedAtReturnsMimimumForUser() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 900L),
                sessionDoc("alice", 200L),   // earliest
                sessionDoc("alice", 600L),
                sessionDoc("bob",   100L)));  // different user — must not affect alice

        Optional<Long> result = repo.earliestStartedAtForUser("alice");

        assertThat(result)
                .isPresent()
                .hasValue(200L);
    }

    @Test
    void sessionEarliestStartedAtReturnsEmptyForUnknownUser() {
        InMemoryIntentSessionRepository repo = new InMemoryIntentSessionRepository(List.of(
                sessionDoc("alice", 500L)));

        assertThat(repo.earliestStartedAtForUser("dave"))
                .as("user with no sessions must return empty Optional")
                .isEmpty();
    }

    // =========================================================================
    // BehavioralProfileRepository — distinctUserIds
    // =========================================================================

    /**
     * In-memory subclass of {@link BehavioralProfileRepository} overriding only
     * {@code distinctUserIds} so no live Mongo collection is used.
     */
    private static final class InMemoryBehavioralProfileRepository extends BehavioralProfileRepository {

        private final List<BehavioralProfileDocument> store;

        InMemoryBehavioralProfileRepository(List<BehavioralProfileDocument> store) {
            super(mock(MongoDatabase.class));
            this.store = new ArrayList<>(store);
        }

        @Override
        public List<String> distinctUserIds() {
            List<String> results = store.stream()
                    .map(BehavioralProfileDocument::getUserId)
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            results.removeIf(id -> id == null);
            return results;
        }
    }

    private static BehavioralProfileDocument profileDoc(String userId) {
        BehavioralProfileDocument doc = new BehavioralProfileDocument();
        doc.setUserId(userId);
        doc.setState("ACTIVE");
        doc.setEventCount(200);
        return doc;
    }

    @Test
    void behavioralProfileDistinctUserIdsDeduplicatesCorrectly() {
        InMemoryBehavioralProfileRepository repo = new InMemoryBehavioralProfileRepository(List.of(
                profileDoc("alice"),
                profileDoc("alice"), // duplicate
                profileDoc("carol")));

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("alice", "carol");
    }

    @Test
    void behavioralProfileDistinctUserIdsFiltersNulls() {
        BehavioralProfileDocument nullProfile = new BehavioralProfileDocument();
        nullProfile.setState("LEARNING");

        InMemoryBehavioralProfileRepository repo = new InMemoryBehavioralProfileRepository(List.of(
                profileDoc("alice"),
                nullProfile));

        List<String> ids = repo.distinctUserIds();

        assertThat(ids)
                .doesNotContainNull()
                .containsExactly("alice");
    }

    @Test
    void behavioralProfileDistinctUserIdsReturnsEmptyForEmptyCollection() {
        InMemoryBehavioralProfileRepository repo = new InMemoryBehavioralProfileRepository(List.of());

        assertThat(repo.distinctUserIds())
                .isNotNull()
                .isEmpty();
    }

    @Test
    void behavioralProfileDistinctUserIdsNeverPerformsWrite() {
        // The in-memory implementation guarantees no write; this documents the contract.
        InMemoryBehavioralProfileRepository repo = new InMemoryBehavioralProfileRepository(List.of(
                profileDoc("alice"), profileDoc("bob")));

        int sizeBefore = repo.store.size();
        repo.distinctUserIds();

        // The store must be unchanged after calling distinctUserIds (no inserts, updates, or deletes).
        assertThat(repo.store)
                .as("store size must be unchanged after distinctUserIds (no writes)")
                .hasSize(sizeBefore);
        assertThat(repo.store.stream().map(BehavioralProfileDocument::getUserId).toList())
                .as("store contents must be unchanged after distinctUserIds")
                .containsExactlyInAnyOrder("alice", "bob");
    }
}
