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
 * Feature: user-profiling-screen, Property 13: Profile assembly is read-only (store contents
 * invariant).
 *
 * <p>Property-based tests for {@link DefaultUserProfileService#assemble(String, ActiveWindow, boolean)}
 * verifying that calling {@code assemble} leaves the contents of all five in-memory fake
 * repositories identical to their contents before the call.
 *
 * <p>Each fake repository records a snapshot of its state before the call; the test asserts that
 * the state after the call is byte-for-byte identical (no inserts, deletes, or updates).
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 9.3
 */
class ReadOnlyAssemblyPropertiesTest {

    private static final String TARGET_USER = "read-only-test-user";

    private SnapshotAuditHistoryRepository auditRepo;
    private SnapshotIntentSessionRepository sessionRepo;
    private SnapshotBehavioralProfileRepository profileRepo;
    private SnapshotAssistAuditRepository assistRepo;
    private SnapshotTranslationRecordRepository translationRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        auditRepo = new SnapshotAuditHistoryRepository();
        sessionRepo = new SnapshotIntentSessionRepository();
        profileRepo = new SnapshotBehavioralProfileRepository();
        assistRepo = new SnapshotAssistAuditRepository();
        translationRepo = new SnapshotTranslationRecordRepository();
        service = new DefaultUserProfileService(
                auditRepo, sessionRepo, profileRepo, assistRepo, translationRepo);
    }

    // -------------------------------------------------------------------------
    // P13: Profile assembly leaves all store contents invariant
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 13: Profile assembly is read-only (store contents
     * invariant).
     *
     * <p>For any pre-populated set of fake repository contents, calling {@code assemble} on a
     * given user and window produces a result, and then the contents of every repository are
     * byte-for-byte identical to their pre-call snapshots (Validates: Requirements 9.3).
     */
    @Property(tries = 100)
    void assembleDoesNotMutateAnyRepositoryState(
            @ForAll("populatedRepoScenarios") PopulatedRepoScenario scenario) {
        // Feature: user-profiling-screen, Property 13: Profile assembly is read-only (store contents invariant)

        // Load data into the fakes
        auditRepo.load(scenario.auditDocs());
        sessionRepo.load(scenario.sessionDocs());
        profileRepo.load(scenario.profileDoc());
        assistRepo.load(scenario.assistDocs());
        translationRepo.load(scenario.translationRecords());

        // Take pre-call snapshots
        List<AuditHistoryDocument> auditBefore = auditRepo.snapshot();
        List<IntentSessionDocument> sessionBefore = sessionRepo.snapshot();
        Optional<BehavioralProfileDocument> profileBefore = profileRepo.snapshot();
        List<AssistAuditDocument> assistBefore = assistRepo.snapshot();
        List<TranslationRecord> translationBefore = translationRepo.snapshot();

        // Call assemble — must not mutate any store
        ActiveWindow window = scenario.window();
        service.assemble(TARGET_USER, window, false);

        // Take post-call snapshots and compare
        List<AuditHistoryDocument> auditAfter = auditRepo.snapshot();
        List<IntentSessionDocument> sessionAfter = sessionRepo.snapshot();
        Optional<BehavioralProfileDocument> profileAfter = profileRepo.snapshot();
        List<AssistAuditDocument> assistAfter = assistRepo.snapshot();
        List<TranslationRecord> translationAfter = translationRepo.snapshot();

        assertThat(auditAfter)
                .as("auditHistoryRepository contents must be invariant after assemble")
                .isEqualTo(auditBefore);
        assertThat(sessionAfter)
                .as("intentSessionRepository contents must be invariant after assemble")
                .isEqualTo(sessionBefore);
        assertThat(profileAfter)
                .as("behavioralProfileRepository contents must be invariant after assemble")
                .isEqualTo(profileBefore);
        assertThat(assistAfter)
                .as("assistAuditRepository contents must be invariant after assemble")
                .isEqualTo(assistBefore);
        assertThat(translationAfter)
                .as("translationRecordRepository contents must be invariant after assemble")
                .isEqualTo(translationBefore);
    }

    /**
     * Feature: user-profiling-screen, Property 13: Profile assembly is read-only (store contents
     * invariant).
     *
     * <p>Variant: full-history window (full=true) also leaves all store contents invariant
     * (Validates: Requirements 9.3).
     */
    @Property(tries = 100)
    void assembleWithFullHistoryDoesNotMutateAnyRepositoryState(
            @ForAll("populatedRepoScenarios") PopulatedRepoScenario scenario) {
        // Feature: user-profiling-screen, Property 13: Profile assembly is read-only (store contents invariant)

        auditRepo.load(scenario.auditDocs());
        sessionRepo.load(scenario.sessionDocs());
        profileRepo.load(scenario.profileDoc());
        assistRepo.load(scenario.assistDocs());
        translationRepo.load(scenario.translationRecords());

        List<AuditHistoryDocument> auditBefore = auditRepo.snapshot();
        List<IntentSessionDocument> sessionBefore = sessionRepo.snapshot();
        Optional<BehavioralProfileDocument> profileBefore = profileRepo.snapshot();
        List<AssistAuditDocument> assistBefore = assistRepo.snapshot();
        List<TranslationRecord> translationBefore = translationRepo.snapshot();

        service.assemble(TARGET_USER, scenario.window(), true);

        assertThat(auditRepo.snapshot())
                .as("audit repo must be invariant after full-history assemble")
                .isEqualTo(auditBefore);
        assertThat(sessionRepo.snapshot())
                .as("session repo must be invariant after full-history assemble")
                .isEqualTo(sessionBefore);
        assertThat(profileRepo.snapshot())
                .as("profile repo must be invariant after full-history assemble")
                .isEqualTo(profileBefore);
        assertThat(assistRepo.snapshot())
                .as("assist repo must be invariant after full-history assemble")
                .isEqualTo(assistBefore);
        assertThat(translationRepo.snapshot())
                .as("translation repo must be invariant after full-history assemble")
                .isEqualTo(translationBefore);
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<PopulatedRepoScenario> populatedRepoScenarios() {
        Arbitrary<Long> windowStart = Arbitraries.longs().between(1_000_000L, 1_000_000_000_000L);
        Arbitrary<Long> windowLength = Arbitraries.longs().between(1L, 86_400_000L);

        return Combinators.combine(windowStart, windowLength)
                .flatAs((start, length) -> {
                    long end = start + length;
                    ActiveWindow window = ActiveWindow.of(start, end);

                    Arbitrary<List<AuditHistoryDocument>> auditDocs =
                            auditDoc(start, end).list().ofMinSize(0).ofMaxSize(8);
                    Arbitrary<List<IntentSessionDocument>> sessionDocs =
                            sessionDoc(start, end).list().ofMinSize(0).ofMaxSize(5);
                    Arbitrary<Optional<BehavioralProfileDocument>> profileDoc =
                            Arbitraries.frequencyOf(
                                    net.jqwik.api.Tuple.of(7, behavioralDoc().map(Optional::of)),
                                    net.jqwik.api.Tuple.of(3, Arbitraries.just(Optional.empty())));
                    Arbitrary<List<AssistAuditDocument>> assistDocs =
                            assistDoc(start, end).list().ofMinSize(0).ofMaxSize(5);
                    Arbitrary<List<TranslationRecord>> translationRecs =
                            translationRecord(start, end, "some intent " + start)
                                    .list().ofMinSize(0).ofMaxSize(5);

                    return Combinators.combine(auditDocs, sessionDocs, profileDoc, assistDocs, translationRecs)
                            .as((ad, sd, pd, asd, tr) ->
                                    new PopulatedRepoScenario(window, ad, sd, pd, asd, tr));
                });
    }

    private static Arbitrary<AuditHistoryDocument> auditDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> id = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(10);
        return Combinators.combine(ts, id).as((t, eid) -> {
            AuditHistoryDocument doc = new AuditHistoryDocument();
            doc.setUserId(TARGET_USER);
            doc.setEventId(eid);
            doc.setCommandText("ls " + eid);
            doc.setTimestamp(t);
            doc.setCorrectiveAction("ALLOW");
            doc.setDivergenceScore(0.1);
            return doc;
        });
    }

    private static Arbitrary<IntentSessionDocument> sessionDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> sid = Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(10);
        return Combinators.combine(ts, sid).as((t, s) -> {
            IntentSessionDocument doc = new IntentSessionDocument();
            doc.setUserId(TARGET_USER);
            doc.setSessionId(s);
            doc.setOriginalDeclaredIntent("intent " + s);
            doc.setDeclaredIntentLanguageTag("hi");
            doc.setStartedAt(t);
            return doc;
        });
    }

    private static Arbitrary<BehavioralProfileDocument> behavioralDoc() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(state -> {
            BehavioralProfileDocument doc = new BehavioralProfileDocument();
            doc.setUserId(TARGET_USER);
            doc.setState("ACTIVE");
            doc.setEventCount(200);
            return doc;
        });
    }

    private static Arbitrary<AssistAuditDocument> assistDoc(long tsMin, long tsMax) {
        Arbitrary<Long> ts = Arbitraries.longs().between(Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        Arbitrary<String> id = Arbitraries.strings().withChars("0123456789abcdef").ofMinLength(8).ofMaxLength(16);
        return Combinators.combine(ts, id).as((t, docId) -> {
            AssistAuditDocument doc = new AssistAuditDocument();
            doc.setId(docId);
            doc.setOperatorId(TARGET_USER);
            doc.setEventType("QUERY");
            doc.setQueryEnglish("query " + docId);
            doc.setGeneratedCommands(List.of("cmd-a", "cmd-b"));
            doc.setTimestamp(t);
            return doc;
        });
    }

    private static Arbitrary<TranslationRecord> translationRecord(long tsMin, long tsMax, String sourceText) {
        Arbitrary<Long> ts = Arbitraries.longs().between(Math.max(0L, tsMin), Math.max(0L, Math.max(tsMin, tsMax)));
        return ts.map(t -> new TranslationRecord(
                sourceText, "translated", LanguageTag.of("hi"), LanguageTag.of("en"),
                "gemini", TranslationRecordKind.INBOUND_INTENT, t));
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    record PopulatedRepoScenario(
            ActiveWindow window,
            List<AuditHistoryDocument> auditDocs,
            List<IntentSessionDocument> sessionDocs,
            Optional<BehavioralProfileDocument> profileDoc,
            List<AssistAuditDocument> assistDocs,
            List<TranslationRecord> translationRecords) {}

    // -------------------------------------------------------------------------
    // Snapshot-tracking fake repositories
    // -------------------------------------------------------------------------

    /**
     * Tracks all writes — a write attempt throws {@link UnsupportedOperationException}, and
     * the snapshot captures a defensive copy before each call so any mutation is detectable.
     */
    static final class SnapshotAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> store = new ArrayList<>();

        SnapshotAuditHistoryRepository() { super(mock(MongoDatabase.class)); }

        void load(List<AuditHistoryDocument> docs) {
            store.clear();
            store.addAll(docs);
        }

        List<AuditHistoryDocument> snapshot() {
            return new ArrayList<>(store);
        }

        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long from, long to) {
            List<AuditHistoryDocument> result = new ArrayList<>();
            for (AuditHistoryDocument doc : store) {
                if (userId.equals(doc.getUserId())
                        && doc.getTimestamp() >= from && doc.getTimestamp() <= to) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public void save(AuditHistoryDocument record) {
            throw new UnsupportedOperationException("SnapshotAuditHistoryRepository is read-only");
        }

        @Override
        public List<String> distinctUserIds() { return List.of(TARGET_USER); }

        @Override
        public Optional<Long> earliestTimestampForUser(String userId) { return Optional.empty(); }
    }

    static final class SnapshotIntentSessionRepository extends IntentSessionRepository {
        private final List<IntentSessionDocument> store = new ArrayList<>();

        SnapshotIntentSessionRepository() { super(mock(MongoDatabase.class)); }

        void load(List<IntentSessionDocument> docs) {
            store.clear();
            store.addAll(docs);
        }

        List<IntentSessionDocument> snapshot() {
            return new ArrayList<>(store);
        }

        @Override
        public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
            List<IntentSessionDocument> result = new ArrayList<>();
            for (IntentSessionDocument doc : store) {
                if (userId.equals(doc.getUserId())
                        && doc.getStartedAt() >= from && doc.getStartedAt() <= to) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public List<String> distinctUserIds() { return List.of(TARGET_USER); }

        @Override
        public Optional<Long> earliestStartedAtForUser(String userId) { return Optional.empty(); }
    }

    static final class SnapshotBehavioralProfileRepository extends BehavioralProfileRepository {
        private Optional<BehavioralProfileDocument> stored = Optional.empty();

        SnapshotBehavioralProfileRepository() { super(mock(MongoDatabase.class)); }

        void load(Optional<BehavioralProfileDocument> doc) {
            this.stored = doc;
        }

        Optional<BehavioralProfileDocument> snapshot() {
            return stored;
        }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return stored;
        }

        @Override
        public List<String> distinctUserIds() { return List.of(TARGET_USER); }
    }

    static final class SnapshotAssistAuditRepository extends AssistAuditRepository {
        private final List<AssistAuditDocument> store = new ArrayList<>();

        SnapshotAssistAuditRepository() { super(mock(MongoDatabase.class)); }

        void load(List<AssistAuditDocument> docs) {
            store.clear();
            store.addAll(docs);
        }

        List<AssistAuditDocument> snapshot() {
            return new ArrayList<>(store);
        }

        @Override
        public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(
                String operatorId, long from, long to) {
            List<AssistAuditDocument> result = new ArrayList<>();
            for (AssistAuditDocument doc : store) {
                if (operatorId.equals(doc.getOperatorId())
                        && doc.getTimestamp() >= from && doc.getTimestamp() <= to) {
                    result.add(doc);
                }
            }
            return result;
        }

        @Override
        public List<String> distinctOperatorIds() { return List.of(TARGET_USER); }

        @Override
        public Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
            return Optional.empty();
        }
    }

    static final class SnapshotTranslationRecordRepository extends TranslationRecordRepository {
        private final List<TranslationRecord> store = new ArrayList<>();

        SnapshotTranslationRecordRepository() { super(mock(MongoDatabase.class)); }

        void load(List<TranslationRecord> records) {
            store.clear();
            store.addAll(records);
        }

        List<TranslationRecord> snapshot() {
            return new ArrayList<>(store);
        }

        @Override
        public List<TranslationRecord> findByTimeRange(long from, long to) {
            List<TranslationRecord> result = new ArrayList<>();
            for (TranslationRecord r : store) {
                if (r.timestamp() >= from && r.timestamp() <= to) {
                    result.add(r);
                }
            }
            return result;
        }
    }

}
