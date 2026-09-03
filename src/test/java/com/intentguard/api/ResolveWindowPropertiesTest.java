package com.intentguard.api;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intentguard.assist.AssistAuditRepository;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.TranslationRecordRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeProperty;

/**
 * Feature: user-profiling-screen, Property 11: Day-window computation and validation.
 * Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
 * persisted record.
 *
 * <p>Property-based tests for {@link DefaultUserProfileService#resolveWindow(String, int, boolean)}.
 * The five repositories are mocked with Mockito so no live MongoDB is required. The service is
 * exercised directly.
 *
 * <p>All tests use jqwik {@code @Property(tries = 100)}, are package-private, use AssertJ, and
 * carry the {@code Feature: user-profiling-screen, Property N: ...} tag.
 *
 * <p>Requirements: 7.1, 7.2, 7.3, 7.4, 7.5
 */
class ResolveWindowPropertiesTest {

    /** Tolerance in milliseconds between the expected {@code now} and the actual window end. */
    private static final long CLOCK_SKEW_MS = 2_000L;

    /** Milliseconds per day, matching the constant in {@link DefaultUserProfileService}. */
    private static final long MILLIS_PER_DAY = 86_400_000L;

    // Mocks re-created before each property execution to avoid cross-property state leakage.
    private AuditHistoryRepository auditRepo;
    private IntentSessionRepository sessionRepo;
    private BehavioralProfileRepository profileRepo;
    private AssistAuditRepository assistRepo;
    private TranslationRecordRepository translationRepo;
    private DefaultUserProfileService service;

    /**
     * Reinitializes mocks and the service before each {@code @Property} method.
     * jqwik's {@link BeforeProperty} runs once per property (before any tries), which is the
     * correct lifecycle for setting up shared collaborators.
     */
    @BeforeProperty
    void setUpService() {
        auditRepo       = mock(AuditHistoryRepository.class);
        sessionRepo     = mock(IntentSessionRepository.class);
        profileRepo     = mock(BehavioralProfileRepository.class);
        assistRepo      = mock(AssistAuditRepository.class);
        translationRepo = mock(TranslationRecordRepository.class);

        service = new DefaultUserProfileService(
                auditRepo, sessionRepo, profileRepo, assistRepo, translationRepo);
    }

    // =========================================================================
    // Property 11: Day-window computation and validation (Req 7.1, 7.2, 7.3)
    // =========================================================================

    /**
     * Feature: user-profiling-screen, Property 11: Day-window computation and validation.
     *
     * <p>For any {@code days} value in [1, 365] with {@code full=false}:
     * <ul>
     *   <li>{@code window.empty()} is {@code false}.</li>
     *   <li>{@code window.end()} ≈ {@code System.currentTimeMillis()} (within 2 s of test start).</li>
     *   <li>{@code window.start()} ≈ {@code end - days * 86_400_000} (within 2 s tolerance).</li>
     * </ul>
     *
     * <p>Validates: Requirements 7.1, 7.2
     */
    @Property(tries = 100)
    void validDaysWindowIsCorrectlyComputed(@ForAll @IntRange(min = 1, max = 365) int days) {
        // Feature: user-profiling-screen, Property 11: Day-window computation and validation

        long beforeCall = System.currentTimeMillis();
        ActiveWindow window = service.resolveWindow("any-user", days, false);
        long afterCall = System.currentTimeMillis();

        // Window must not be empty (Req 7.2).
        assertThat(window.empty())
                .as("day-window with days=%d must not be empty", days)
                .isFalse();

        // Window end ≈ now: must be within the call duration plus tolerance.
        assertThat(window.end())
                .as("window.end() must be within [beforeCall-skew, afterCall+skew] for days=%d", days)
                .isBetween(beforeCall - CLOCK_SKEW_MS, afterCall + CLOCK_SKEW_MS);

        // Window start ≈ end - days*MILLIS_PER_DAY (Req 7.1).
        long expectedStartMin = beforeCall - (long) days * MILLIS_PER_DAY - CLOCK_SKEW_MS;
        long expectedStartMax = afterCall  - (long) days * MILLIS_PER_DAY + CLOCK_SKEW_MS;
        assertThat(window.start())
                .as("window.start() must be ≈ now - %d*MILLIS_PER_DAY for days=%d", days, days)
                .isBetween(expectedStartMin, expectedStartMax);

        // The span (end - start) must equal days * MILLIS_PER_DAY within tolerance.
        long span = window.end() - window.start();
        assertThat(span)
                .as("span (end - start) must be ≈ %d * MILLIS_PER_DAY", days)
                .isBetween((long) days * MILLIS_PER_DAY - CLOCK_SKEW_MS,
                           (long) days * MILLIS_PER_DAY + CLOCK_SKEW_MS);
    }

    /**
     * Feature: user-profiling-screen, Property 11: Day-window computation and validation.
     *
     * <p>For any {@code days} value strictly outside [1, 365] with {@code full=false}:
     * {@link InvalidWindowException} is thrown carrying the rejected value, and no state mutation
     * occurs (Req 7.3, 9.3).
     *
     * <p>Validates: Requirements 7.3
     */
    @Property(tries = 100)
    void invalidDaysThrowsInvalidWindowException(@ForAll("outOfRangeDays") int days) {
        // Feature: user-profiling-screen, Property 11: Day-window computation and validation

        assertThatThrownBy(() -> service.resolveWindow("any-user", days, false))
                .as("days=%d is out of [1, 365] and must throw InvalidWindowException", days)
                .isInstanceOf(InvalidWindowException.class)
                .satisfies(ex -> assertThat(((InvalidWindowException) ex).getInvalidDays())
                        .as("exception must carry the rejected days value")
                        .isEqualTo(days));
    }

    /**
     * Generates {@code days} values outside [1, 365]: values ≤ 0 and values ≥ 366, bounded to
     * keep the generator finite and well-distributed.
     */
    @Provide
    Arbitrary<Integer> outOfRangeDays() {
        Arbitrary<Integer> tooSmall = Arbitraries.integers().between(-1000, 0);
        Arbitrary<Integer> tooLarge = Arbitraries.integers().between(366, 10_000);
        return Arbitraries.oneOf(tooSmall, tooLarge);
    }

    // =========================================================================
    // Property 12: Full-history window lower bound (Req 7.4, 7.5)
    // =========================================================================

    /**
     * Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
     * persisted record.
     *
     * <p>For any combination of per-store earliest timestamps (where at least one is present),
     * {@code resolveWindow(userId, any, true)} returns a non-empty window whose {@code start()}
     * equals the minimum across the three stores, and whose {@code end()} ≈ now.
     *
     * <p>Validates: Requirements 7.4
     */
    @Property(tries = 100)
    void fullWindowStartIsMinimumAcrossStores(
            @ForAll("optionalTimestamps") Optional<Long> auditTs,
            @ForAll("optionalTimestamps") Optional<Long> sessionTs,
            @ForAll("optionalTimestamps") Optional<Long> assistTs) {

        // Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
        // persisted record

        // Skip when ALL three are empty — that case is covered by fullWindowIsEmptyWhenAllStoresReturnEmpty.
        Assume.that(auditTs.isPresent() || sessionTs.isPresent() || assistTs.isPresent());

        stubStoreEarliests(auditTs, sessionTs, assistTs);

        long beforeCall = System.currentTimeMillis();
        ActiveWindow window = service.resolveWindow("user-x", 3, true);
        long afterCall = System.currentTimeMillis();

        // Window must NOT be empty.
        assertThat(window.empty())
                .as("window with at least one present record must not be empty")
                .isFalse();

        // window.start() == min of all present timestamps (Req 7.4).
        long expectedMin = minPresent(auditTs, sessionTs, assistTs);
        assertThat(window.start())
                .as("window.start() must equal the minimum timestamp across all stores")
                .isEqualTo(expectedMin);

        // window.end() ≈ now.
        assertThat(window.end())
                .as("window.end() must be ≈ now")
                .isBetween(beforeCall - CLOCK_SKEW_MS, afterCall + CLOCK_SKEW_MS);
    }

    /**
     * Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
     * persisted record.
     *
     * <p>When all three stores return empty for a user and {@code full=true},
     * {@link ActiveWindow#empty()} must be {@code true} (Req 7.5).
     *
     * <p>Validates: Requirements 7.5
     */
    @Property(tries = 100)
    void fullWindowIsEmptyWhenAllStoresReturnEmpty(@ForAll("userIds") String userId) {
        // Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
        // persisted record

        stubStoreEarliests(Optional.empty(), Optional.empty(), Optional.empty());

        ActiveWindow window = service.resolveWindow(userId, 3, true);

        assertThat(window.empty())
                .as("window must be empty when all stores return no records for user='%s'", userId)
                .isTrue();
    }

    /**
     * Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
     * persisted record.
     *
     * <p>When only one of the three stores has a record, {@code window.start()} must equal that
     * store's timestamp — a single source is sufficient to anchor the lower bound.
     *
     * <p>Validates: Requirements 7.4
     */
    @Property(tries = 100)
    void fullWindowStartEqualsOnlyPresentStore(
            @ForAll("storeIndex") int presentStoreIndex,
            @ForAll("positiveTimestamps") long ts) {

        // Feature: user-profiling-screen, Property 12: Full-history window lower bound is the earliest
        // persisted record

        Optional<Long> auditTs   = presentStoreIndex == 0 ? Optional.of(ts) : Optional.empty();
        Optional<Long> sessionTs = presentStoreIndex == 1 ? Optional.of(ts) : Optional.empty();
        Optional<Long> assistTs  = presentStoreIndex == 2 ? Optional.of(ts) : Optional.empty();

        stubStoreEarliests(auditTs, sessionTs, assistTs);

        ActiveWindow window = service.resolveWindow("user-y", 7, true);

        assertThat(window.empty()).isFalse();
        assertThat(window.start())
                .as("window.start() must equal the single present store's timestamp ts=%d (store=%d)",
                        ts, presentStoreIndex)
                .isEqualTo(ts);
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Generates {@link Optional}{@code <Long>} values: approximately 60% present (epoch-ms in a
     * realistic range), 40% empty.
     */
    @Provide
    Arbitrary<Optional<Long>> optionalTimestamps() {
        Arbitrary<Long> ts = Arbitraries.longs().between(1_000_000L, 2_000_000_000_000L);
        Arbitrary<Optional<Long>> present = ts.map(Optional::of);
        Arbitrary<Optional<Long>> absent  = Arbitraries.just(Optional.empty());
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, present),
                net.jqwik.api.Tuple.of(4, absent));
    }

    /** Generates positive timestamps (epoch ms in a reasonable range). */
    @Provide
    Arbitrary<Long> positiveTimestamps() {
        return Arbitraries.longs().between(1_000L, 2_000_000_000_000L);
    }

    /** Generates 0, 1, or 2 — the index of the single present store. */
    @Provide
    Arbitrary<Integer> storeIndex() {
        return Arbitraries.of(0, 1, 2);
    }

    /** Generates short non-blank user ids for readability in failure messages. */
    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Stubs all three repository earliest-timestamp methods for any user id so that the generated
     * Optional values are returned by the service under test.
     */
    private void stubStoreEarliests(
            Optional<Long> auditTs, Optional<Long> sessionTs, Optional<Long> assistTs) {
        when(auditRepo.earliestTimestampForUser(anyString())).thenReturn(auditTs);
        when(sessionRepo.earliestStartedAtForUser(anyString())).thenReturn(sessionTs);
        when(assistRepo.earliestQueryTimestampForOperator(anyString())).thenReturn(assistTs);
    }

    /**
     * Returns the minimum long value among the {@link Optional} instances that are present.
     * Caller is responsible for ensuring at least one is present.
     */
    private static long minPresent(Optional<Long> a, Optional<Long> b, Optional<Long> c) {
        long min = Long.MAX_VALUE;
        if (a.isPresent()) min = Math.min(min, a.get());
        if (b.isPresent()) min = Math.min(min, b.get());
        if (c.isPresent()) min = Math.min(min, c.get());
        return min;
    }
}
