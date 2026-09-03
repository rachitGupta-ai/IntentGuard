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

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeProperty;

/**
 * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with
 * a correct truncation flag.
 *
 * <p>Property-based tests verifying that each per-category pipeline method never returns more than
 * {@link DefaultUserProfileService#RECORD_CAP} records, and correctly sets {@code truncated} and
 * {@code totalAvailable} on the resulting {@link CategoryView}.
 *
 * <p>Tests generate both below-cap and above-cap inputs to exercise both paths.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, and use AssertJ.
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 5.6
 */
class BoundedResultsPropertiesTest {

    private static final String TARGET_USER = "cap-test-user";
    private static final int RECORD_CAP = DefaultUserProfileService.RECORD_CAP;

    /**
     * Fixed wide window covering all timestamps we generate — avoids window filtering
     * interfering with the cap test.
     */
    private static final ActiveWindow WIDE_WINDOW = ActiveWindow.of(0L, Long.MAX_VALUE / 2);

    private FakeAuditHistoryRepository auditRepo;
    private FakeIntentSessionRepository sessionRepo;
    private FakeAssistAuditRepository assistRepo;
    private FakeTranslationRecordRepository translationRepo;
    private DefaultUserProfileService service;

    @BeforeProperty
    void setUp() {
        auditRepo = new FakeAuditHistoryRepository();
        sessionRepo = new FakeIntentSessionRepository();
        assistRepo = new FakeAssistAuditRepository();
        translationRepo = new FakeTranslationRecordRepository();
        service = new DefaultUserProfileService(
                auditRepo,
                sessionRepo,
                new NoOpBehavioralProfileRepository(),
                assistRepo,
                translationRepo);
    }

    // -------------------------------------------------------------------------
    // P9a: commandTimeline never exceeds RECORD_CAP
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap
     * with a correct truncation flag.
     *
     * <p>For any count of in-window audit documents (including counts greater than RECORD_CAP),
     * {@code assembleCommandTimeline} returns at most RECORD_CAP records, sets {@code truncated}
     * iff count > RECORD_CAP, and sets {@code totalAvailable} to the actual count.
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3
     */
    @Property(tries = 100)
    void commandTimelineIsBoundedByRecordCap(@ForAll @IntRange(min = 0, max = 700) int count) {
        // Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with a correct truncation flag

        List<AuditHistoryDocument> docs = generateAuditDocs(count);
        auditRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<CommandDecisionEntry> view =
                service.assembleCommandTimeline(TARGET_USER, WIDE_WINDOW);

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.records().size())
                .as("commandTimeline must return at most %d records (had %d)", RECORD_CAP, count)
                .isLessThanOrEqualTo(RECORD_CAP);
        assertThat(view.totalAvailable())
                .as("totalAvailable must equal actual count %d", count)
                .isEqualTo(count);
        assertThat(view.truncated())
                .as("truncated must be %b when count=%d vs cap=%d", count > RECORD_CAP, count, RECORD_CAP)
                .isEqualTo(count > RECORD_CAP);
    }

    // -------------------------------------------------------------------------
    // P9b: assembleAssistQueries never exceeds RECORD_CAP
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap
     * with a correct truncation flag.
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3
     */
    @Property(tries = 100)
    void assistQueriesIsBoundedByRecordCap(@ForAll @IntRange(min = 0, max = 700) int count) {
        // Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with a correct truncation flag

        List<AssistAuditDocument> docs = generateAssistDocs(count);
        assistRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<AssistQueryView> view =
                service.assembleAssistQueries(TARGET_USER, WIDE_WINDOW);

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.records().size())
                .as("assistQueries must return at most %d records (had %d)", RECORD_CAP, count)
                .isLessThanOrEqualTo(RECORD_CAP);
        assertThat(view.totalAvailable())
                .as("totalAvailable must equal actual count %d", count)
                .isEqualTo(count);
        assertThat(view.truncated())
                .as("truncated must be %b when count=%d vs cap=%d", count > RECORD_CAP, count, RECORD_CAP)
                .isEqualTo(count > RECORD_CAP);
    }

    // -------------------------------------------------------------------------
    // P9c: assembleMultilingual never exceeds RECORD_CAP
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap
     * with a correct truncation flag.
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3
     */
    @Property(tries = 100)
    void multilingualIsBoundedByRecordCap(@ForAll @IntRange(min = 0, max = 700) int count) {
        // Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with a correct truncation flag

        List<IntentSessionDocument> docs = generateSessionDocs(count);
        sessionRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<MultilingualEntryView> view =
                service.assembleMultilingual(TARGET_USER, WIDE_WINDOW);

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        // Multilingual filters to non-English supported sessions; count <= docs count
        assertThat(view.records().size())
                .as("multilingual must return at most %d records", RECORD_CAP)
                .isLessThanOrEqualTo(RECORD_CAP);
        // totalAvailable = count of sessions that pass the multilingual filter
        assertThat(view.totalAvailable())
                .as("totalAvailable must be >= records.size()")
                .isGreaterThanOrEqualTo(view.records().size());
        assertThat(view.truncated())
                .as("truncated must be consistent with totalAvailable and cap")
                .isEqualTo(view.totalAvailable() > RECORD_CAP);
    }

    // -------------------------------------------------------------------------
    // P9d: assembleTranslations never exceeds RECORD_CAP (with correlated sessions)
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap
     * with a correct truncation flag.
     *
     * <p>Validates: Requirements 8.1, 8.2, 8.3, 5.6
     */
    @Property(tries = 100)
    void translationsIsBoundedByRecordCap(@ForAll @IntRange(min = 0, max = 700) int count) {
        // Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with a correct truncation flag

        // Set up one session with a known intent so translations can correlate
        String intent = "check server status";
        IntentSessionDocument session = makeSessionDoc("s-0", intent, 1_000L);
        sessionRepo.setDocumentsForUser(TARGET_USER, List.of(session));

        List<TranslationRecord> records = generateTranslationRecords(count, intent);
        translationRepo.setRecordsForWindow(records);

        CategoryView<TranslationRecordView> view =
                service.assembleTranslations(TARGET_USER, WIDE_WINDOW);

        assertThat(view.status()).isEqualTo(CategoryStatus.OK);
        assertThat(view.records().size())
                .as("translations must return at most %d records (had %d)", RECORD_CAP, count)
                .isLessThanOrEqualTo(RECORD_CAP);
        assertThat(view.totalAvailable())
                .as("totalAvailable must equal actual count %d", count)
                .isEqualTo(count);
        assertThat(view.truncated())
                .as("truncated must be %b when count=%d vs cap=%d", count > RECORD_CAP, count, RECORD_CAP)
                .isEqualTo(count > RECORD_CAP);
    }

    // -------------------------------------------------------------------------
    // P9e: When exactly RECORD_CAP records exist, truncated is false
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap
     * with a correct truncation flag.
     *
     * <p>Boundary: when the input size is exactly RECORD_CAP, truncated must be false and
     * all records are returned (Validates: Requirements 8.1, 8.2, 8.3).
     */
    @Property(tries = 10)
    void exactlyCapRecordsNotTruncated() {
        // Feature: user-profiling-screen, Property 9: Each category is bounded by Record_Cap with a correct truncation flag

        List<AuditHistoryDocument> docs = generateAuditDocs(RECORD_CAP);
        auditRepo.setDocumentsForUser(TARGET_USER, docs);

        CategoryView<CommandDecisionEntry> view =
                service.assembleCommandTimeline(TARGET_USER, WIDE_WINDOW);

        assertThat(view.truncated())
                .as("exactly %d records must not be truncated", RECORD_CAP)
                .isFalse();
        assertThat(view.records().size())
                .as("all %d records must be returned when count == cap", RECORD_CAP)
                .isEqualTo(RECORD_CAP);
        assertThat(view.totalAvailable())
                .isEqualTo(RECORD_CAP);
    }

    // -------------------------------------------------------------------------
    // Data builders
    // -------------------------------------------------------------------------

    private static List<AuditHistoryDocument> generateAuditDocs(int count) {
        List<AuditHistoryDocument> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AuditHistoryDocument doc = new AuditHistoryDocument();
            doc.setUserId(TARGET_USER);
            doc.setEventId("evt-" + i);
            doc.setCommandText("ls " + i);
            doc.setTimestamp(1_000_000L + i);
            doc.setCorrectiveAction("ALLOW");
            doc.setDivergenceScore(0.1);
            list.add(doc);
        }
        return list;
    }

    private static List<AssistAuditDocument> generateAssistDocs(int count) {
        List<AssistAuditDocument> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AssistAuditDocument doc = new AssistAuditDocument();
            doc.setId(String.format("%024x", i)); // hex-like id
            doc.setOperatorId(TARGET_USER);
            doc.setEventType("QUERY");
            doc.setQueryEnglish("query " + i);
            doc.setGeneratedCommands(List.of("cmd" + i));
            doc.setTimestamp(1_000_000L + i);
            list.add(doc);
        }
        return list;
    }

    /** Generates documents that will pass the multilingual filter (non-English supported tag). */
    private static List<IntentSessionDocument> generateSessionDocs(int count) {
        String[] tags = {"hi", "bn", "te", "mr", "ta"};
        List<IntentSessionDocument> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            IntentSessionDocument doc = new IntentSessionDocument();
            doc.setUserId(TARGET_USER);
            doc.setSessionId("s-" + i);
            doc.setOriginalDeclaredIntent("intent " + i);
            doc.setDeclaredIntentLanguageTag(tags[i % tags.length]);
            doc.setStartedAt(1_000_000L + i);
            list.add(doc);
        }
        return list;
    }

    private static List<TranslationRecord> generateTranslationRecords(int count, String sourceText) {
        List<TranslationRecord> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new TranslationRecord(
                    sourceText,
                    "translated-" + i,
                    LanguageTag.of("hi"),
                    LanguageTag.of("en"),
                    "gemini",
                    TranslationRecordKind.INBOUND_INTENT,
                    1_000_000L + i));
        }
        return list;
    }

    private static IntentSessionDocument makeSessionDoc(String sid, String intent, long ts) {
        IntentSessionDocument doc = new IntentSessionDocument();
        doc.setUserId(TARGET_USER);
        doc.setSessionId(sid);
        doc.setOriginalDeclaredIntent(intent);
        doc.setDeclaredIntentLanguageTag("hi");
        doc.setStartedAt(ts);
        return doc;
    }

    // -------------------------------------------------------------------------
    // Fake / no-op repositories
    // -------------------------------------------------------------------------

    static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private String storedUserId;
        private List<AuditHistoryDocument> storedDocs = List.of();

        FakeAuditHistoryRepository() { super(mock(MongoDatabase.class)); }

        void setDocumentsForUser(String userId, List<AuditHistoryDocument> docs) {
            this.storedUserId = userId;
            this.storedDocs = new ArrayList<>(docs);
        }

        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long from, long to) {
            if (!userId.equals(storedUserId)) return List.of();
            List<AuditHistoryDocument> result = new ArrayList<>();
            for (AuditHistoryDocument doc : storedDocs) {
                if (doc.getTimestamp() >= from && doc.getTimestamp() <= to) {
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
        public Optional<Long> earliestTimestampForUser(String userId) {
            return Optional.empty();
        }
    }

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

    static final class NoOpBehavioralProfileRepository extends BehavioralProfileRepository {
        NoOpBehavioralProfileRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            return Optional.empty();
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }
    }
}
