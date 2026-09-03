package com.intentguard.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;
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

/**
 * Tests for {@link DefaultUserProfileService#computeRiskStats(String)} — the average command score
 * and 30-day risk trend feeding the User_Profiling_Screen (Req 9.3, 9.5).
 */
class RiskStatsTest {

    private static final long DAY_MS = 86_400_000L;

    private FakeAuditHistoryRepository auditRepo;
    private DefaultUserProfileService service;

    private DefaultUserProfileService build(FakeAuditHistoryRepository repo) {
        return new DefaultUserProfileService(
                repo,
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());
    }

    @Test
    void absentWhenNoCommands() {
        auditRepo = new FakeAuditHistoryRepository(List.of());
        service = build(auditRepo);

        RiskStats stats = service.computeRiskStats("ravi");

        assertThat(stats.present()).isFalse();
        assertThat(stats.commandCount()).isZero();
        assertThat(stats.averageScore()).isZero();
        assertThat(stats.riskBand()).isEqualTo("NONE");
        // The daily series is still continuous so the graph renders an axis.
        assertThat(stats.daily()).hasSize(RiskStats.WINDOW_DAYS);
    }

    @Test
    void averageAndActionCountsAreComputed() {
        long now = System.currentTimeMillis();
        List<AuditHistoryDocument> docs = new ArrayList<>();
        docs.add(auditDoc("ravi", 0.10, "ALLOW", now - 1 * DAY_MS));
        docs.add(auditDoc("ravi", 0.50, "ASK",   now - 1 * DAY_MS));
        docs.add(auditDoc("ravi", 0.90, "BLOCK", now - 2 * DAY_MS));
        auditRepo = new FakeAuditHistoryRepository(docs);
        service = build(auditRepo);

        RiskStats stats = service.computeRiskStats("ravi");

        assertThat(stats.present()).isTrue();
        assertThat(stats.commandCount()).isEqualTo(3);
        assertThat(stats.allowCount()).isEqualTo(1);
        assertThat(stats.askCount()).isEqualTo(1);
        assertThat(stats.blockCount()).isEqualTo(1);
        assertThat(stats.averageScore()).isCloseTo((0.10 + 0.50 + 0.90) / 3.0, within(1e-9));
        assertThat(stats.riskBand()).isEqualTo("ELEVATED"); // 0.5 avg → ELEVATED
        assertThat(stats.windowDays()).isEqualTo(30);
    }

    @Test
    void dailySeriesIsContinuousOldestFirstAndBucketsByDay() {
        long now = System.currentTimeMillis();
        // two commands on the same (yesterday) day, one on the day before
        List<AuditHistoryDocument> docs = List.of(
                auditDoc("ravi", 0.20, "ALLOW", now - 1 * DAY_MS),
                auditDoc("ravi", 0.40, "ASK",   now - 1 * DAY_MS),
                auditDoc("ravi", 0.80, "BLOCK", now - 2 * DAY_MS));
        auditRepo = new FakeAuditHistoryRepository(docs);
        service = build(auditRepo);

        RiskStats stats = service.computeRiskStats("ravi");

        List<DailyRiskPoint> daily = stats.daily();
        assertThat(daily).hasSize(RiskStats.WINDOW_DAYS);
        // oldest-first: epochDayMs strictly increases
        for (int i = 1; i < daily.size(); i++) {
            assertThat(daily.get(i).epochDayMs()).isGreaterThan(daily.get(i - 1).epochDayMs());
        }
        // total commands across the series equals the input count
        int totalCount = daily.stream().mapToInt(DailyRiskPoint::count).sum();
        assertThat(totalCount).isEqualTo(3);
        // the day with two commands has their mean
        DailyRiskPoint twoCmdDay = daily.stream().filter(p -> p.count() == 2).findFirst().orElseThrow();
        assertThat(twoCmdDay.averageScore()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void bandForThresholds() {
        assertThat(RiskStats.bandFor(0.0)).isEqualTo("LOW");
        assertThat(RiskStats.bandFor(0.39)).isEqualTo("LOW");
        assertThat(RiskStats.bandFor(0.4)).isEqualTo("ELEVATED");
        assertThat(RiskStats.bandFor(0.79)).isEqualTo("ELEVATED");
        assertThat(RiskStats.bandFor(0.8)).isEqualTo("HIGH");
        assertThat(RiskStats.bandFor(1.0)).isEqualTo("HIGH");
    }

    // ---- helpers -------------------------------------------------------------

    private static AuditHistoryDocument auditDoc(String userId, double score, String action, long ts) {
        AuditHistoryDocument d = new AuditHistoryDocument();
        d.setUserId(userId);
        d.setEventId("evt-" + ts + "-" + action);
        d.setCommandText("cmd");
        d.setDivergenceScore(score);
        d.setCorrectiveAction(action);
        d.setTimestamp(ts);
        return d;
    }

    static final class FakeAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> store;
        FakeAuditHistoryRepository(List<AuditHistoryDocument> store) {
            super(mock(MongoDatabase.class));
            this.store = new ArrayList<>(store);
        }
        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long from, long to) {
            List<AuditHistoryDocument> out = new ArrayList<>();
            for (AuditHistoryDocument d : store) {
                if (userId.equals(d.getUserId()) && d.getTimestamp() >= from && d.getTimestamp() <= to) {
                    out.add(d);
                }
            }
            return out;
        }
        @Override public List<String> distinctUserIds() { return List.of(); }
        @Override public Optional<Long> earliestTimestampForUser(String userId) { return Optional.empty(); }
    }

    static final class NoOpIntentSessionRepository extends IntentSessionRepository {
        NoOpIntentSessionRepository() { super(mock(MongoDatabase.class)); }
        @Override public List<IntentSessionDocument> findByUserIdAndTimeRange(String u, long f, long t) { return List.of(); }
        @Override public List<String> distinctUserIds() { return List.of(); }
        @Override public Optional<Long> earliestStartedAtForUser(String u) { return Optional.empty(); }
    }

    static final class NoOpBehavioralProfileRepository extends BehavioralProfileRepository {
        NoOpBehavioralProfileRepository() { super(mock(MongoDatabase.class)); }
        @Override public Optional<BehavioralProfileDocument> findByUserId(String u) { return Optional.empty(); }
        @Override public List<String> distinctUserIds() { return List.of(); }
    }

    static final class NoOpAssistAuditRepository extends AssistAuditRepository {
        NoOpAssistAuditRepository() { super(mock(MongoDatabase.class)); }
        @Override public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(String o, long f, long t) { return List.of(); }
        @Override public List<String> distinctOperatorIds() { return List.of(); }
        @Override public Optional<Long> earliestQueryTimestampForOperator(String o) { return Optional.empty(); }
    }

    static final class NoOpTranslationRecordRepository extends TranslationRecordRepository {
        NoOpTranslationRecordRepository() { super(mock(MongoDatabase.class)); }
        @Override public List<TranslationRecord> findByTimeRange(long f, long t) { return List.of(); }
    }
}
