package com.intentguard.api;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
 *
 * <p>Tests for {@link DefaultUserProfileService#assemble(String, ActiveWindow, boolean)}
 * verifying:
 * <ul>
 *   <li>When at least one category succeeds, {@code profileLoadFailed} is {@code false}
 *       (Req 10.3).</li>
 *   <li>When all five categories fail (timeout/exception), {@code profileLoadFailed} is
 *       {@code true} (Req 10.4).</li>
 *   <li>Categories that fail return {@link CategoryStatus#UNAVAILABLE} with empty records;
 *       categories that succeed return {@link CategoryStatus#OK} (Req 10.2, 10.3).</li>
 * </ul>
 *
 * <p>Note on repository sharing: {@code assembleMultilingual} and {@code assembleTranslations}
 * both read from {@code IntentSessionRepository}. Therefore the failure flags map to repository
 * failure as follows:
 * <ul>
 *   <li>{@code auditFails} → commandTimeline category fails</li>
 *   <li>{@code sessionFails} → both multilingual AND translations categories fail</li>
 *   <li>{@code assistFails} → assistQueries category fails</li>
 *   <li>{@code behavioralFails} → behavioral profile task fails</li>
 * </ul>
 *
 * <p>Tests use JUnit 5 {@code @Test} for deterministic invariants, and jqwik {@code @Property}
 * for generative coverage. Package-private. AssertJ assertions.
 *
 * <p>Validates: Requirements 10.3, 10.4
 */
class PartialFailurePropertiesTest {

    private static final String TARGET_USER = "failure-test-user";
    private static final ActiveWindow WINDOW = ActiveWindow.of(1_000_000L, 2_000_000_000_000L);

    // -------------------------------------------------------------------------
    // P14a: All categories fail → profileLoadFailed = true
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>When all repositories throw, all five category tasks fail, and
     * {@code profileLoadFailed} must be {@code true} (Validates: Requirements 10.4).
     */
    @Test
    void allCategoriesFailYieldsProfileLoadFailedTrue() {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        DefaultUserProfileService service = new DefaultUserProfileService(
                new ThrowingAuditHistoryRepository(),
                new ThrowingIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(), // behavioral OK but…
                new ThrowingAssistAuditRepository(),
                new ThrowingTranslationRecordRepository());

        // behavioral naturally absent is OK, but failing is different — use throwing behavioral too
        DefaultUserProfileService allFail = new DefaultUserProfileService(
                new ThrowingAuditHistoryRepository(),
                new ThrowingIntentSessionRepository(),
                new ThrowingBehavioralProfileRepository(),
                new ThrowingAssistAuditRepository(),
                new ThrowingTranslationRecordRepository());

        UserProfileView view = allFail.assemble(TARGET_USER, WINDOW, false);

        assertThat(view.profileLoadFailed())
                .as("profileLoadFailed must be true when all categories throw")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // P14b: Only audit fails → profileLoadFailed = false, others OK
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>When only the audit history repository throws (commandTimeline fails), all other
     * categories succeed, so {@code profileLoadFailed} is {@code false} (Req 10.3).
     */
    @Test
    void onlyAuditFailsYieldsPartialProfile() {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        DefaultUserProfileService service = new DefaultUserProfileService(
                new ThrowingAuditHistoryRepository(),
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());

        UserProfileView view = service.assemble(TARGET_USER, WINDOW, false);

        assertThat(view.profileLoadFailed())
                .as("profileLoadFailed must be false when only commandTimeline fails")
                .isFalse();

        assertThat(view.commandTimeline().status())
                .as("commandTimeline must be UNAVAILABLE (its repo threw)")
                .isEqualTo(CategoryStatus.UNAVAILABLE);

        assertThat(view.commandTimeline().records())
                .as("UNAVAILABLE category must have empty records")
                .isEmpty();

        assertThat(view.multilingual().status())
                .as("multilingual must be OK (session repo did not throw)")
                .isEqualTo(CategoryStatus.OK);

        assertThat(view.assistQueries().status())
                .as("assistQueries must be OK (assist repo did not throw)")
                .isEqualTo(CategoryStatus.OK);
    }

    // -------------------------------------------------------------------------
    // P14c: Only assist fails → profileLoadFailed = false
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>When only the assist audit repository throws, four categories still succeed, so
     * {@code profileLoadFailed} is {@code false} (Req 10.3).
     */
    @Test
    void onlyAssistFailsYieldsPartialProfile() {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        DefaultUserProfileService service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new ThrowingAssistAuditRepository(),
                new NoOpTranslationRecordRepository());

        UserProfileView view = service.assemble(TARGET_USER, WINDOW, false);

        assertThat(view.profileLoadFailed())
                .as("profileLoadFailed must be false when only assistQueries fails")
                .isFalse();

        assertThat(view.assistQueries().status())
                .as("assistQueries must be UNAVAILABLE")
                .isEqualTo(CategoryStatus.UNAVAILABLE);

        assertThat(view.commandTimeline().status())
                .as("commandTimeline must be OK")
                .isEqualTo(CategoryStatus.OK);
    }

    // -------------------------------------------------------------------------
    // P14d: Session repo fails → multilingual + translations UNAVAILABLE, but others OK
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>When the intent session repository throws, both multilingual and translations fail
     * (they share the session repo). If audit and assist succeed, {@code profileLoadFailed}
     * is still {@code false} (Req 10.3).
     */
    @Test
    void sessionFailsYieldsMultilingualAndTranslationsUnavailable() {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        DefaultUserProfileService service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                new ThrowingIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());

        UserProfileView view = service.assemble(TARGET_USER, WINDOW, false);

        assertThat(view.profileLoadFailed())
                .as("profileLoadFailed must be false when at least audit+assist succeed")
                .isFalse();

        assertThat(view.multilingual().status())
                .as("multilingual must be UNAVAILABLE when session repo throws")
                .isEqualTo(CategoryStatus.UNAVAILABLE);

        assertThat(view.commandTimeline().status())
                .as("commandTimeline must be OK (unaffected)")
                .isEqualTo(CategoryStatus.OK);

        assertThat(view.assistQueries().status())
                .as("assistQueries must be OK (unaffected)")
                .isEqualTo(CategoryStatus.OK);
    }

    // -------------------------------------------------------------------------
    // P14e: Naturally absent behavioral does not cause profileLoadFailed
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>A naturally absent behavioral profile (no document found, not a task failure) does NOT
     * set {@code profileLoadFailed} regardless of other categories (Validates: Req 10.4).
     */
    @Test
    void naturallyAbsentBehavioralDoesNotCauseProfileLoadFailed() {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        DefaultUserProfileService service = new DefaultUserProfileService(
                new NoOpAuditHistoryRepository(),
                new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(),
                new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());

        UserProfileView view = service.assemble(TARGET_USER, WINDOW, false);

        assertThat(view.profileLoadFailed()).isFalse();
        assertThat(view.behavioralProfile().present()).isFalse();
    }

    // -------------------------------------------------------------------------
    // P14f: Property — any combination with at least one OK group → not profileLoadFailed
    // -------------------------------------------------------------------------

    /**
     * Feature: user-profiling-screen, Property 14: Partial-failure semantics.
     *
     * <p>For any combination where at least one of {audit, session, assist} repos succeeds,
     * the assembled profile has {@code profileLoadFailed = false} (Validates: Req 10.3).
     *
     * <p>We use three independent boolean flags. When {@code auditFails ∧ sessionFails ∧
     * assistFails} the only remaining categories are behavioral profile (which succeeds here —
     * naturally absent) and translations-without-sessions (UNAVAILABLE). In that case
     * {@code profileLoadFailed = false} because behavioral succeeded (naturally absent ≠ failed).
     */
    @Property(tries = 100)
    void anySucceedingCategoryPreventsProfileLoadFailed(
            @ForAll("repoFailureTriples") RepoFailureTriple triple) {
        // Feature: user-profiling-screen, Property 14: Partial-failure semantics

        // When session repo throws, translations also fails — but we still have the behavioral.
        // The behavioral is always "naturally absent" here (NoOp returns empty), which is NOT
        // a failure, so profileLoadFailed can never be true in this property.
        DefaultUserProfileService service = new DefaultUserProfileService(
                triple.auditFails() ? new ThrowingAuditHistoryRepository() : new NoOpAuditHistoryRepository(),
                triple.sessionFails() ? new ThrowingIntentSessionRepository() : new NoOpIntentSessionRepository(),
                new NoOpBehavioralProfileRepository(), // always naturally absent — never fails
                triple.assistFails() ? new ThrowingAssistAuditRepository() : new NoOpAssistAuditRepository(),
                new NoOpTranslationRecordRepository());

        UserProfileView view = service.assemble(TARGET_USER, WINDOW, false);

        // Because behavioral repo is always NoOp (returns absent), it never throws.
        // Therefore at least the behavioral task always succeeds (naturally absent ≠ failed),
        // and profileLoadFailed must always be false regardless of the other three.
        assertThat(view.profileLoadFailed())
                .as("profileLoadFailed must be false: behavioral always succeeds (naturally absent). flags=%s", triple)
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<RepoFailureTriple> repoFailureTriples() {
        return Arbitraries.of(true, false).tuple3()
                .map(t -> new RepoFailureTriple(t.get1(), t.get2(), t.get3()));
    }

    record RepoFailureTriple(boolean auditFails, boolean sessionFails, boolean assistFails) {}

    // -------------------------------------------------------------------------
    // Throwing repositories — simulate category failure
    // -------------------------------------------------------------------------

    static final class ThrowingAuditHistoryRepository extends AuditHistoryRepository {
        ThrowingAuditHistoryRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long from, long to) {
            throw new RuntimeException("simulated audit history failure");
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }

        @Override
        public Optional<Long> earliestTimestampForUser(String userId) { return Optional.empty(); }
    }

    static final class ThrowingIntentSessionRepository extends IntentSessionRepository {
        ThrowingIntentSessionRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
            throw new RuntimeException("simulated intent session failure");
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }

        @Override
        public Optional<Long> earliestStartedAtForUser(String userId) { return Optional.empty(); }
    }

    static final class ThrowingBehavioralProfileRepository extends BehavioralProfileRepository {
        ThrowingBehavioralProfileRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public Optional<BehavioralProfileDocument> findByUserId(String userId) {
            throw new RuntimeException("simulated behavioral profile failure");
        }

        @Override
        public List<String> distinctUserIds() { return List.of(); }
    }

    static final class ThrowingAssistAuditRepository extends AssistAuditRepository {
        ThrowingAssistAuditRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<AssistAuditDocument> findQueriesByOperatorAndTimeRange(
                String operatorId, long from, long to) {
            throw new RuntimeException("simulated assist audit failure");
        }

        @Override
        public List<String> distinctOperatorIds() { return List.of(); }

        @Override
        public Optional<Long> earliestQueryTimestampForOperator(String operatorId) {
            return Optional.empty();
        }
    }

    static final class ThrowingTranslationRecordRepository extends TranslationRecordRepository {
        ThrowingTranslationRecordRepository() { super(mock(MongoDatabase.class)); }

        @Override
        public List<TranslationRecord> findByTimeRange(long from, long to) {
            throw new RuntimeException("simulated translation record failure");
        }
    }

    // -------------------------------------------------------------------------
    // No-op repositories
    // -------------------------------------------------------------------------

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
