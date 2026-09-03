package com.intentguard.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.springframework.stereotype.Service;

import com.intentguard.assist.AssistAuditDocument;
import com.intentguard.assist.AssistAuditRepository;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.persistence.IntentSessionRepository;
import com.intentguard.translation.SupportedLanguages;
import com.intentguard.translation.TranslationRecord;
import com.intentguard.translation.TranslationRecordRepository;

import jakarta.annotation.PreDestroy;

/**
 * Default implementation of {@link UserProfileService}.
 *
 * <p>Stateless and read-only: the five constructor-injected repositories are read via pure
 * query methods only; no scoring, decision, translation, or execution collaborator is referenced
 * (Req 9.4). A small bounded {@link ExecutorService} (5 threads) is used exclusively to apply
 * independent per-category time budgets during {@link #assemble} (Req 10.2). It is shut down
 * cleanly on Spring context close via {@link #shutdown()} (Req 9.3).
 */
@Service
public class DefaultUserProfileService implements UserProfileService {

    /** Milliseconds per day used when computing day-based Active_Windows (Req 7.1). */
    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * Maximum number of records returned per category (Req 8.1).
     * Aligned with {@code InsightController.BOOTSTRAP_MAX_RECORDS}.
     */
    static final int RECORD_CAP = 500;

    /** Per-category time budget in milliseconds before yielding UNAVAILABLE (Req 10.2). */
    private static final long CATEGORY_TIMEOUT_MS = 5_000L;

    private static final Logger LOG = Logger.getLogger(DefaultUserProfileService.class.getName());

    private final AuditHistoryRepository auditHistoryRepository;
    private final IntentSessionRepository intentSessionRepository;
    private final BehavioralProfileRepository behavioralProfileRepository;
    private final AssistAuditRepository assistAuditRepository;
    private final TranslationRecordRepository translationRecordRepository;

    /** Supported languages set used by the multilingual projection filter (Req 3.1). */
    private final SupportedLanguages supportedLanguages;

    /** Bounded executor for per-category parallel assembly with independent time budgets. */
    private final ExecutorService categoryExecutor;

    /**
     * Constructs the service with all required repositories and a freshly created thread pool.
     *
     * @param auditHistoryRepository      Audit_History store (Req 2.1, 7.4, 9.3)
     * @param intentSessionRepository     Intent_Session store (Req 3.1, 7.4, 9.3)
     * @param behavioralProfileRepository Behavioral_Profile store (Req 6.1, 9.3)
     * @param assistAuditRepository       Assist_Audit store — QUERY records only (Req 4.1, 7.4, 9.3)
     * @param translationRecordRepository Translation_Record store (Req 5.1, 9.3)
     */
    public DefaultUserProfileService(
            AuditHistoryRepository auditHistoryRepository,
            IntentSessionRepository intentSessionRepository,
            BehavioralProfileRepository behavioralProfileRepository,
            AssistAuditRepository assistAuditRepository,
            TranslationRecordRepository translationRecordRepository) {
        this.auditHistoryRepository = auditHistoryRepository;
        this.intentSessionRepository = intentSessionRepository;
        this.behavioralProfileRepository = behavioralProfileRepository;
        this.assistAuditRepository = assistAuditRepository;
        this.translationRecordRepository = translationRecordRepository;
        this.supportedLanguages = SupportedLanguages.defaults();
        this.categoryExecutor = Executors.newFixedThreadPool(5);
    }

    /**
     * Shuts down the category executor cleanly on Spring context close.
     * Outstanding tasks are interrupted; the executor does not accept new submissions after this.
     */
    @PreDestroy
    public void shutdown() {
        categoryExecutor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // UserProfileService — listKnownUsers (Req 1.1, 1.2, 1.3)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Unions the distinct user identifiers from Audit_History, Behavioral_Profile,
     * Intent_Session, and Assist_Audit (QUERY records). Translation_Record contributes nothing
     * (no {@code userId} is stored there). The union is deduplicated case-insensitively and sorted
     * case-insensitively ascending by {@link KnownUsersView#from(java.util.Collection)} (Req 1.1–1.3).
     */
    @Override
    public KnownUsersView listKnownUsers() {
        List<String> union = new ArrayList<>();
        union.addAll(auditHistoryRepository.distinctUserIds());
        union.addAll(behavioralProfileRepository.distinctUserIds());
        union.addAll(intentSessionRepository.distinctUserIds());
        union.addAll(assistAuditRepository.distinctOperatorIds());
        // Translation_Record contributes no identifiers — no userId is stored (Req 1.1 design note).
        return KnownUsersView.from(union);
    }

    // -----------------------------------------------------------------------
    // UserProfileService — resolveWindow (Req 7.1–7.5)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Resolution rules:
     * <ul>
     *   <li>{@code full == false}: validates {@code 1 ≤ days ≤ 365} (Req 7.2, 7.3), then
     *       returns {@code [now - days * 86_400_000L, now]} (Req 7.1).</li>
     *   <li>{@code full == true}: computes the lower bound as the minimum of the three
     *       per-store earliest-timestamp results across Audit_History, Intent_Session, and
     *       Assist_Audit QUERY records (Req 7.4). Returns {@link ActiveWindow#emptyWindow()} when
     *       no records exist across all three stores (Req 7.5).</li>
     * </ul>
     *
     * @throws InvalidWindowException if {@code full == false} and {@code days ∉ [1, 365]} (Req 7.3)
     */
    @Override
    public ActiveWindow resolveWindow(String userId, int days, boolean full) {
        long now = System.currentTimeMillis();

        if (!full) {
            // Req 7.2, 7.3: validate the day range; no state mutation on rejection (Req 9.3).
            if (days < 1 || days > 365) {
                throw new InvalidWindowException(days);
            }
            // Req 7.1: window = [now - days * MILLIS_PER_DAY, now].
            return ActiveWindow.of(now - days * MILLIS_PER_DAY, now);
        }

        // Req 7.4: full-history window — lower bound is the earliest persisted user-keyed record.
        OptionalLong lower = earliestTimestampAcrossStores(userId);
        if (lower.isEmpty()) {
            // Req 7.5: no records exist for the user; return the empty sentinel window.
            return ActiveWindow.emptyWindow();
        }
        return ActiveWindow.of(lower.getAsLong(), now);
    }

    /**
     * Returns the minimum timestamp across Audit_History, Intent_Session, and Assist_Audit QUERY
     * records for the given user. Returns an empty {@link OptionalLong} when all three stores
     * report no records for the user (Req 7.4, 7.5).
     *
     * @param userId the user identifier to query
     * @return the minimum epoch-ms timestamp, or empty when the user has no persisted records
     */
    private OptionalLong earliestTimestampAcrossStores(String userId) {
        Optional<Long> auditEarliest = auditHistoryRepository.earliestTimestampForUser(userId);
        Optional<Long> sessionEarliest = intentSessionRepository.earliestStartedAtForUser(userId);
        Optional<Long> assistEarliest = assistAuditRepository.earliestQueryTimestampForOperator(userId);

        // Find the minimum across whichever stores have records for this user.
        long min = Long.MAX_VALUE;
        boolean found = false;

        if (auditEarliest.isPresent()) {
            min = Math.min(min, auditEarliest.get());
            found = true;
        }
        if (sessionEarliest.isPresent()) {
            min = Math.min(min, sessionEarliest.get());
            found = true;
        }
        if (assistEarliest.isPresent()) {
            min = Math.min(min, assistEarliest.get());
            found = true;
        }

        return found ? OptionalLong.of(min) : OptionalLong.empty();
    }

    // -----------------------------------------------------------------------
    // UserProfileService — assemble (Req 10.2, 10.3, 10.4, 9.4)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Runs each of the five activity categories on the bounded {@link #categoryExecutor} in
     * parallel. Every category future is awaited with an independent 5-second cutoff (Req 10.2).
     * A timeout ({@link TimeoutException}) or any execution failure ({@link ExecutionException})
     * yields {@link CategoryView#unavailable()} for that category; sibling categories are
     * unaffected (Req 10.3). The behavioral-profile task yields
     * {@link BehavioralProfileView#absent()} on failure (there is no {@code CategoryView} wrapper
     * for this category).
     *
     * <p>{@link UserProfileView#profileLoadFailed()} is set {@code true} only when all four
     * {@link CategoryView}-wrapped categories are {@link CategoryStatus#UNAVAILABLE} <em>and</em>
     * the behavioral profile future itself threw or timed out (i.e. all five tasks failed)
     * (Req 10.4). A naturally absent profile (no document in the store) is not a task failure.
     *
     * <p>Reads only; never invokes scoring, decision, translation, or execution (Req 9.4).
     */
    @Override
    public UserProfileView assemble(String userId, ActiveWindow window, boolean fullHistory) {
        // Submit all five category tasks to the bounded executor in parallel.
        Future<CategoryView<CommandDecisionEntry>> timelineFuture =
                categoryExecutor.submit(() -> assembleCommandTimeline(userId, window));
        Future<CategoryView<MultilingualEntryView>> multilingualFuture =
                categoryExecutor.submit(() -> assembleMultilingual(userId, window));
        Future<CategoryView<AssistQueryView>> assistFuture =
                categoryExecutor.submit(() -> assembleAssistQueries(userId, window));
        Future<CategoryView<TranslationRecordView>> translationsFuture =
                categoryExecutor.submit(() -> assembleTranslations(userId, window));
        // Wrap behavioral in a BehavioralResult so we can distinguish task-failure from
        // naturally-absent (the latter is not a failure for profileLoadFailed purposes, Req 10.4).
        Future<BehavioralResult> profileFuture =
                categoryExecutor.submit(() -> new BehavioralResult(assembleBehavioralProfile(userId), false));
        // Risk statistics (average score + 30-day trend) run in parallel with the categories.
        Future<RiskStats> riskFuture =
                categoryExecutor.submit(() -> computeRiskStats(userId));

        // Collect results — each future gets an independent 5s cutoff (Req 10.2).
        CategoryView<CommandDecisionEntry> commandTimeline = awaitCategory(timelineFuture, "commandTimeline");
        CategoryView<MultilingualEntryView> multilingual   = awaitCategory(multilingualFuture, "multilingual");
        CategoryView<AssistQueryView>       assistQueries  = awaitCategory(assistFuture, "assistQueries");
        CategoryView<TranslationRecordView> translations   = awaitCategory(translationsFuture, "translations");
        BehavioralResult behavioralResult = awaitBehavioralResult(profileFuture);
        RiskStats riskStats = awaitRiskStats(riskFuture);

        // profileLoadFailed = true only when ALL five tasks fail (Req 10.4).
        boolean allCategoryViewsFailed =
                commandTimeline.status() == CategoryStatus.UNAVAILABLE
                        && multilingual.status()  == CategoryStatus.UNAVAILABLE
                        && assistQueries.status() == CategoryStatus.UNAVAILABLE
                        && translations.status()  == CategoryStatus.UNAVAILABLE;
        boolean profileLoadFailed = allCategoryViewsFailed && behavioralResult.taskFailed();

        return new UserProfileView(
                userId,
                window.start(),
                window.end(),
                fullHistory,
                window.empty(),
                profileLoadFailed,
                commandTimeline,
                multilingual,
                assistQueries,
                translations,
                behavioralResult.view(),
                riskStats);
    }

    /**
     * Awaits the risk-stats future with the same {@link #CATEGORY_TIMEOUT_MS} cutoff. On timeout or
     * failure returns an empty (absent) {@link RiskStats} with a continuous but empty 30-day series,
     * so the graph still renders an axis rather than breaking the whole profile (Req 10.3).
     */
    private RiskStats awaitRiskStats(Future<RiskStats> future) {
        try {
            return future.get(CATEGORY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warning("Risk stats exceeded 5s cutoff — returning absent");
            future.cancel(true);
            return RiskStats.absent(emptyDailySeries(System.currentTimeMillis()));
        } catch (ExecutionException | InterruptedException e) {
            LOG.warning("Risk stats threw during assembly — returning absent: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return RiskStats.absent(emptyDailySeries(System.currentTimeMillis()));
        }
    }

    /**
     * Awaits a {@link CategoryView} future with a {@link #CATEGORY_TIMEOUT_MS} cutoff. Returns
     * {@link CategoryView#unavailable()} on timeout or exception (Req 10.2, 10.3).
     */
    private static <T> CategoryView<T> awaitCategory(Future<CategoryView<T>> future, String categoryName) {
        try {
            return future.get(CATEGORY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warning("Category '" + categoryName + "' exceeded 5s cutoff — returning UNAVAILABLE");
            future.cancel(true);
            return CategoryView.unavailable();
        } catch (ExecutionException | InterruptedException e) {
            LOG.warning("Category '" + categoryName + "' threw during assembly — returning UNAVAILABLE: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CategoryView.unavailable();
        }
    }

    /**
     * Awaits the behavioral profile future with a {@link #CATEGORY_TIMEOUT_MS} cutoff. On
     * failure returns a {@link BehavioralResult} wrapping {@link BehavioralProfileView#absent()}
     * with {@code taskFailed = true} so the caller can include it in the all-failed check
     * (Req 10.4). A successful result always has {@code taskFailed = false} regardless of whether
     * the profile was naturally absent (no document in the store).
     */
    private static BehavioralResult awaitBehavioralResult(Future<BehavioralResult> future) {
        try {
            return future.get(CATEGORY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warning("Category 'behavioralProfile' exceeded 5s cutoff — returning absent");
            future.cancel(true);
            return new BehavioralResult(BehavioralProfileView.absent(), true);
        } catch (ExecutionException | InterruptedException e) {
            LOG.warning("Category 'behavioralProfile' threw during assembly — returning absent: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new BehavioralResult(BehavioralProfileView.absent(), true);
        }
    }

    /**
     * Carrier for the behavioral profile task result, distinguishing a task failure from a
     * naturally absent profile (Req 10.4).
     *
     * @param view       the projected view; never null
     * @param taskFailed {@code true} when the future timed out or threw; {@code false} otherwise
     */
    private record BehavioralResult(BehavioralProfileView view, boolean taskFailed) {}

    // -----------------------------------------------------------------------
    // Per-category pipeline methods (package-private for testability, Req 8.1-8.3)
    // -----------------------------------------------------------------------

    /**
     * Command timeline pipeline: fetch → map → select most-recent RECORD_CAP → display-order.
     *
     * <p>Attribute: {@code AuditHistoryRepository.queryByUserAndTimeRange} is already user-keyed
     * and window-filtered at the store level (Req 2.1).
     * Select: sort by (timestamp desc, eventId desc), take top RECORD_CAP (Req 8.1).
     * Display-order: oldest-first by (timestamp asc, eventId asc) (Req 2.1).
     *
     * @param userId the user identifier
     * @param window the resolved Active_Window
     * @return bounded, display-ordered command timeline (Req 2.1, 8.1, 8.2, 8.3)
     */
    CategoryView<CommandDecisionEntry> assembleCommandTimeline(String userId, ActiveWindow window) {
        if (window.empty()) {
            return CategoryView.of(List.of(), false, 0);
        }

        List<AuditHistoryDocument> raw =
                auditHistoryRepository.queryByUserAndTimeRange(userId, window.start(), window.end());

        List<CommandDecisionEntry> all = new ArrayList<>(raw.size());
        for (AuditHistoryDocument doc : raw) {
            all.add(CommandDecisionEntry.from(doc));
        }

        int totalAvailable = all.size();
        boolean truncated = totalAvailable > RECORD_CAP;

        // Select most-recent RECORD_CAP: sort (timestamp desc, eventId desc), take top 500
        List<CommandDecisionEntry> capped = selectMostRecent(all, RECORD_CAP,
                Comparator.comparingLong(CommandDecisionEntry::timestamp).reversed()
                        .thenComparing(Comparator.comparing(CommandDecisionEntry::eventId).reversed()));

        // Display-order: oldest-first (timestamp asc, eventId asc) (Req 2.1)
        capped.sort(Comparator.comparingLong(CommandDecisionEntry::timestamp)
                .thenComparing(CommandDecisionEntry::eventId));

        return CategoryView.of(capped, truncated, totalAvailable);
    }

    /**
     * Multilingual pipeline: fetch intent sessions → filter via MultilingualEntryView.from →
     * select most-recent RECORD_CAP → display-order most-recent-first.
     *
     * <p>Attribute: {@code IntentSessionRepository.findByUserIdAndTimeRange} is user-keyed (Req 3.1).
     * Filter: {@code MultilingualEntryView.from} excludes non-attributable, English, or unsupported sessions.
     * Select: sort by (timestamp desc, sessionId desc), take top RECORD_CAP (Req 8.1).
     * Display-order: most-recent-first by (timestamp desc, sessionId desc) (Req 3.1).
     *
     * @param userId the user identifier
     * @param window the resolved Active_Window
     * @return bounded, display-ordered multilingual entries (Req 3.1, 8.1, 8.2, 8.3)
     */
    CategoryView<MultilingualEntryView> assembleMultilingual(String userId, ActiveWindow window) {
        if (window.empty()) {
            return CategoryView.of(List.of(), false, 0);
        }

        List<IntentSessionDocument> sessions =
                intentSessionRepository.findByUserIdAndTimeRange(userId, window.start(), window.end());

        List<MultilingualEntryView> all = new ArrayList<>();
        for (IntentSessionDocument session : sessions) {
            MultilingualEntryView.from(session, supportedLanguages).ifPresent(all::add);
        }

        int totalAvailable = all.size();
        boolean truncated = totalAvailable > RECORD_CAP;

        // Select most-recent RECORD_CAP: sort (timestamp desc, sessionId desc), take top 500
        List<MultilingualEntryView> capped = selectMostRecent(all, RECORD_CAP,
                Comparator.comparingLong(MultilingualEntryView::timestamp).reversed()
                        .thenComparing(Comparator.comparing(MultilingualEntryView::sessionId).reversed()));

        // Display-order: most-recent-first (timestamp desc, sessionId desc) (Req 3.1)
        capped.sort(Comparator.comparingLong(MultilingualEntryView::timestamp).reversed()
                .thenComparing(Comparator.comparing(MultilingualEntryView::sessionId).reversed()));

        return CategoryView.of(capped, truncated, totalAvailable);
    }

    /**
     * Assist queries pipeline: fetch QUERY records → map → select most-recent RECORD_CAP →
     * display-order oldest-first.
     *
     * <p>Attribute: {@code AssistAuditRepository.findQueriesByOperatorAndTimeRange} is
     * operator-keyed and already window-filtered. Ties broken by ascending _id hex (Req 4.1).
     * Select: sort by (timestamp desc, id desc), take top RECORD_CAP (Req 8.1).
     * Display-order: oldest-first by (timestamp asc, id asc) (Req 4.1).
     *
     * @param userId the user identifier (operator id)
     * @param window the resolved Active_Window
     * @return bounded, display-ordered assist query entries (Req 4.1, 8.1, 8.2, 8.3)
     */
    CategoryView<AssistQueryView> assembleAssistQueries(String userId, ActiveWindow window) {
        if (window.empty()) {
            return CategoryView.of(List.of(), false, 0);
        }

        List<AssistAuditDocument> raw =
                assistAuditRepository.findQueriesByOperatorAndTimeRange(userId, window.start(), window.end());

        List<AssistQueryView> all = new ArrayList<>(raw.size());
        for (AssistAuditDocument doc : raw) {
            all.add(AssistQueryView.from(doc));
        }

        int totalAvailable = all.size();
        boolean truncated = totalAvailable > RECORD_CAP;

        // Select most-recent RECORD_CAP: sort (timestamp desc, id desc), take top 500
        List<AssistQueryView> capped = selectMostRecent(all, RECORD_CAP,
                Comparator.comparingLong(AssistQueryView::timestamp).reversed()
                        .thenComparing(Comparator.comparing(AssistQueryView::id).reversed()));

        // Display-order: oldest-first (timestamp asc, id asc) (Req 4.1)
        capped.sort(Comparator.comparingLong(AssistQueryView::timestamp)
                .thenComparing(AssistQueryView::id));

        return CategoryView.of(capped, truncated, totalAvailable);
    }

    /**
     * Translation pipeline: fetch all translation_records in window → correlate to user via
     * intent sessions → map → select most-recent RECORD_CAP → display-order oldest-first.
     *
     * <p>Correlation: a translation record is attributed to the user only when its
     * {@code sourceText} matches one of the user's {@code originalDeclaredIntent} values from
     * their Intent_Sessions within the same window (Req 5.3). Records that do not correlate
     * are excluded.
     *
     * <p>Tie-breaking uses a stable content key: concatenation of all identifying fields (Req 5.1).
     * Display-order: oldest-first by (timestamp asc, content-key asc) (Req 5.1).
     *
     * @param userId the user identifier
     * @param window the resolved Active_Window
     * @return bounded, display-ordered translation record entries (Req 5.1, 5.3, 8.1, 8.2, 8.3)
     */
    CategoryView<TranslationRecordView> assembleTranslations(String userId, ActiveWindow window) {
        if (window.empty()) {
            return CategoryView.of(List.of(), false, 0);
        }

        // Build the correlation key set: originalDeclaredIntent values for this user in the window
        List<IntentSessionDocument> userSessions =
                intentSessionRepository.findByUserIdAndTimeRange(userId, window.start(), window.end());
        Set<String> correlationKeys = new HashSet<>();
        for (IntentSessionDocument s : userSessions) {
            if (s.getOriginalDeclaredIntent() != null && !s.getOriginalDeclaredIntent().isBlank()) {
                correlationKeys.add(s.getOriginalDeclaredIntent());
            }
        }

        // Fetch all translation records in window, keep only those correlated to the user (Req 5.3)
        List<TranslationRecord> allInWindow =
                translationRecordRepository.findByTimeRange(window.start(), window.end());

        List<TranslationRecordView> all = new ArrayList<>();
        for (TranslationRecord r : allInWindow) {
            if (correlationKeys.contains(r.sourceText())) {
                all.add(TranslationRecordView.from(r));
            }
        }

        int totalAvailable = all.size();
        boolean truncated = totalAvailable > RECORD_CAP;

        // Select most-recent RECORD_CAP: sort by (timestamp desc, content-key desc), take top 500
        List<TranslationRecordView> capped = selectMostRecent(all, RECORD_CAP,
                Comparator.comparingLong(TranslationRecordView::timestamp).reversed()
                        .thenComparing(Comparator.comparing(DefaultUserProfileService::translationContentKey).reversed()));

        // Display-order: oldest-first (timestamp asc, content-key asc) (Req 5.1)
        capped.sort(Comparator.comparingLong(TranslationRecordView::timestamp)
                .thenComparing(DefaultUserProfileService::translationContentKey));

        return CategoryView.of(capped, truncated, totalAvailable);
    }

    /**
     * Behavioral profile pipeline: fetch by userId → project to view.
     *
     * <p>Unlike the other categories this returns a {@link BehavioralProfileView} directly (not
     * wrapped in {@link CategoryView}) because the profile is a single summary object rather than
     * a pageable list of records (Req 6.1, 6.4, 6.5).
     *
     * @param userId the user identifier
     * @return populated view when a profile exists, otherwise {@link BehavioralProfileView#absent()}
     */
    BehavioralProfileView assembleBehavioralProfile(String userId) {
        return behavioralProfileRepository.findByUserId(userId)
                .map(BehavioralProfileView::from)
                .orElseGet(BehavioralProfileView::absent);
    }

    // -----------------------------------------------------------------------
    // Risk statistics: average command score + 30-day trend (read-only, Req 9.3, 9.5)
    // -----------------------------------------------------------------------

    /** UTC day formatter (yyyy-MM-dd) for bucketing the trend series. */
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** Milliseconds in one day, used for day bucketing. */
    private static final long DAY_MS = 86_400_000L;

    /**
     * Computes {@link RiskStats} for the user over a fixed trailing 30-day window ending now,
     * independent of the operator-selected display window. Reads the user's {@code audit_history}
     * command decisions once, then derives:
     * <ul>
     *   <li>the mean divergence score and total command count (the "average command score");</li>
     *   <li>ALLOW / ASK / BLOCK counts;</li>
     *   <li>a coarse risk band via {@link RiskStats#bandFor(double)};</li>
     *   <li>a continuous per-day series (one point per calendar day, empty days included) for the
     *       trend graph.</li>
     * </ul>
     *
     * <p>Pure read — never writes and never invokes scoring/decision/translation/execution paths
     * (Req 9.3, 9.4). When the user has no commands in the window a {@link RiskStats#absent} value
     * carrying the empty-but-continuous daily series is returned so the graph still renders an axis.
     *
     * @param userId the user to summarise
     * @return the computed risk statistics; never {@code null}
     */
    RiskStats computeRiskStats(String userId) {
        long now = System.currentTimeMillis();
        long from = now - (long) RiskStats.WINDOW_DAYS * DAY_MS;

        List<AuditHistoryDocument> docs =
                auditHistoryRepository.queryByUserAndTimeRange(userId, from, now);

        // Pre-seed a continuous day->accumulator map so empty days still appear on the graph.
        // Keyed by the UTC day-start epoch-ms (truncated), oldest-first via LinkedHashMap.
        long todayStart = (now / DAY_MS) * DAY_MS;
        long firstDayStart = todayStart - (long) (RiskStats.WINDOW_DAYS - 1) * DAY_MS;
        Map<Long, double[]> byDay = new LinkedHashMap<>(); // dayStart -> [sumScore, count]
        for (long d = firstDayStart; d <= todayStart; d += DAY_MS) {
            byDay.put(d, new double[] {0.0, 0.0});
        }

        double totalScore = 0.0;
        int count = 0;
        int allow = 0;
        int ask = 0;
        int block = 0;

        for (AuditHistoryDocument doc : docs) {
            double score = doc.getDivergenceScore();
            totalScore += score;
            count++;

            String action = doc.getCorrectiveAction();
            if ("ALLOW".equals(action)) {
                allow++;
            } else if ("ASK".equals(action)) {
                ask++;
            } else if ("BLOCK".equals(action)) {
                block++;
            }

            long dayStart = (doc.getTimestamp() / DAY_MS) * DAY_MS;
            double[] acc = byDay.get(dayStart);
            if (acc != null) { // guard against clock-edge records outside the seeded range
                acc[0] += score;
                acc[1] += 1.0;
            }
        }

        List<DailyRiskPoint> daily = toDailyPoints(byDay);

        if (count == 0) {
            return RiskStats.absent(daily);
        }

        double average = totalScore / count;
        return new RiskStats(
                true,
                average,
                count,
                allow,
                ask,
                block,
                RiskStats.bandFor(average),
                RiskStats.WINDOW_DAYS,
                daily);
    }

    /** Converts the day-accumulator map into an oldest-first list of {@link DailyRiskPoint}. */
    private static List<DailyRiskPoint> toDailyPoints(Map<Long, double[]> byDay) {
        List<DailyRiskPoint> points = new ArrayList<>(byDay.size());
        for (Map.Entry<Long, double[]> e : byDay.entrySet()) {
            long dayStart = e.getKey();
            double sum = e.getValue()[0];
            int c = (int) e.getValue()[1];
            double avg = c == 0 ? 0.0 : sum / c;
            points.add(new DailyRiskPoint(DAY_FMT.format(Instant.ofEpochMilli(dayStart)), dayStart, c, avg));
        }
        return points;
    }

    /**
     * Builds an empty-but-continuous 30-day series ending at {@code now}, used as the fallback when
     * risk-stats computation times out or fails so the graph still renders an axis (Req 10.3).
     */
    private static List<DailyRiskPoint> emptyDailySeries(long now) {
        long todayStart = (now / DAY_MS) * DAY_MS;
        long firstDayStart = todayStart - (long) (RiskStats.WINDOW_DAYS - 1) * DAY_MS;
        List<DailyRiskPoint> points = new ArrayList<>(RiskStats.WINDOW_DAYS);
        for (long d = firstDayStart; d <= todayStart; d += DAY_MS) {
            points.add(new DailyRiskPoint(DAY_FMT.format(Instant.ofEpochMilli(d)), d, 0, 0.0));
        }
        return points;
    }

    // -----------------------------------------------------------------------
    // Shared pipeline utilities
    // -----------------------------------------------------------------------

    /**
     * Sorts {@code items} by {@code selectionOrder} and returns the first {@code cap} elements
     * as a mutable list. The original list is not modified.
     *
     * <p>This implements the "select most-recent RECORD_CAP" step: the caller provides a
     * descending (newest-first) comparator, and this method returns the top {@code cap} elements,
     * which are the most recent ones (Req 8.1).
     *
     * @param <T>            item type
     * @param items          the full in-window list
     * @param cap            maximum number of items to return (Req 8.1)
     * @param selectionOrder sort order for selection; caller passes descending = newest-first
     * @return mutable list of at most {@code cap} items, sorted by {@code selectionOrder}
     */
    private static <T> List<T> selectMostRecent(List<T> items, int cap, Comparator<T> selectionOrder) {
        if (items.size() <= cap) {
            return new ArrayList<>(items);
        }
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(selectionOrder);
        return new ArrayList<>(sorted.subList(0, cap));
    }

    /**
     * Builds a stable content key for a {@link TranslationRecordView} used for deterministic
     * tie-breaking when multiple records share the same timestamp (Req 5.1).
     *
     * <p>The key concatenates all identifying fields with the ASCII Unit Separator (U+001F), which
     * cannot appear in natural field values, guaranteeing two distinct records always differ.
     *
     * @param v the translation record view
     * @return a stable, non-null string key
     */
    private static String translationContentKey(TranslationRecordView v) {
        return v.timestamp()
                + "\u001F" + nullToEmpty(v.sourceText())
                + "\u001F" + nullToEmpty(v.translatedText())
                + "\u001F" + nullToEmpty(v.sourceLanguageTag())
                + "\u001F" + nullToEmpty(v.targetLanguageTag())
                + "\u001F" + nullToEmpty(v.kind());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
