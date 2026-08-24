package com.intentguard.profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.ProfileState;
import com.intentguard.domain.ScoringContext;
import com.intentguard.persistence.BehavioralProfileDocument;
import com.intentguard.persistence.BehavioralProfileRepository;
import com.intentguard.persistence.TimingPatternsDocument;
import com.intentguard.scoring.CommandNormalizer;
import com.intentguard.scoring.ProfileSnapshot;
import com.intentguard.scoring.ProfileSnapshotProvider;

/**
 * Behavioral_Profile Manager (Req 3.1, 3.2, 3.3, 3.5).
 *
 * <p>Maintains each user's learned "behavioral DNA": command vocabulary counts, command sequencing
 * statistics (bigrams over {@link CommandNormalizer#normalizedToken(String) normalized tokens},
 * keyed {@code "prevToken>currToken"}), the typed-vs-pasted ratio by command category, timing
 * patterns (24-bucket hour-of-day histogram and mean inter-command interval), and the context tags
 * each command category is observed in (derived from cwd/repo/env).
 *
 * <h2>Update policy — only on allowed events (Req 3.2)</h2>
 * <p>A user's profile is updated for exactly the Command_Events that were <em>allowed</em>.
 * {@link #recordAllowedEvent} performs the update unconditionally (its caller has already decided
 * ALLOW); {@link #recordEvent} is the policy-aware entry point that updates only when the
 * Corrective_Action is {@link CorrectiveAction#ALLOW} and is a no-op for {@code ASK}/{@code BLOCK}.
 *
 * <h2>Learning state (Req 3.3)</h2>
 * <p>A profile is {@link ProfileState#LEARNING} while its {@code eventCount} is below the
 * configured minimum, and {@link ProfileState#ACTIVE} at or above it. The state is stamped onto the
 * persisted profile on each update and can be queried per-score via
 * {@link #profileStateFor(String, int)} so the scoring path can report it on every score.
 *
 * <h2>Persistence (Req 3.5)</h2>
 * <p>Profiles are persisted to and reloaded from the Datastore through
 * {@link BehavioralProfileRepository}, which keys profiles by {@code userId} and upserts in place,
 * so a profile survives an engine restart. Reconstructing a manager against the same repository
 * (as happens after a restart) sees the full persisted profile.
 *
 * <h2>Scoring bridge</h2>
 * <p>This manager is a {@link ProfileSnapshotProvider}: it adapts the persisted profile document
 * into the read-only {@link ProfileSnapshot} the deterministic scoring components consume. The
 * snapshot's {@link ProfileSnapshot#lastCommandToken() lastCommandToken} is the normalized token of
 * the user's most recent allowed command, so Sequence_Surprise can form the next bigram transition.
 *
 * <h2>Concurrency</h2>
 * <p>Reads and writes take a per-user lock so a profile's load-modify-persist cycle is atomic even
 * when scoring and recording race across threads. In-memory continuity state (last token / last
 * timestamp per user) is held in concurrent maps; it seeds bigram and inter-command-interval
 * updates and is rebuilt naturally after a restart from the first recorded event onward.
 */
@Service
public class BehavioralProfileManager implements ProfileSnapshotProvider {

    /**
     * Exponential-moving-average weight applied to a newly observed typed(1.0)/pasted(0.0) sample
     * when updating a category's typed-vs-pasted ratio. Small enough that an established ratio moves
     * gradually, but a first observation seeds the ratio directly to the observed value. Persisting
     * a single ratio (rather than raw counts) keeps the update stable across restarts.
     */
    static final double RATIO_SMOOTHING = 0.1;

    /** EMA weight applied to a newly observed inter-command interval when updating the mean. */
    static final double TIMING_SMOOTHING = 0.2;

    /** Context tag applied to a command category observed inside a repository. */
    static final String TAG_REPO_DIR = "repoDir";

    /** Context tag applied to a command category observed under a user home directory. */
    static final String TAG_HOME = "home";

    /** Context tag applied to a command category observed in some other working directory. */
    static final String TAG_WORKING_DIR = "workingDir";

    private static final int HOURS_IN_DAY = 24;

    private final BehavioralProfileRepository repository;
    private volatile Clock clock = Clock.systemUTC();

    // Per-user continuity for building "prev>curr" bigrams and inter-command intervals. These are
    // derived/continuity fields (not the source of truth); the persisted document is authoritative.
    private final ConcurrentHashMap<String, String> lastTokenByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTimestampByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public BehavioralProfileManager(BehavioralProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /** Test seam: overrides the clock used to stamp {@code updatedAt} on persisted profiles. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Policy-aware entry point (Req 3.2): update the user's Behavioral_Profile only when
     * {@code action} is {@link CorrectiveAction#ALLOW}. For {@code ASK} or {@code BLOCK} this is a
     * no-op, so a flagged or blocked command never influences the learned baseline.
     *
     * @param event             the scored Command_Event
     * @param action            the Corrective_Action the Decision Engine applied
     * @param learningMinEvents the configured minimum event count for the ACTIVE state
     */
    public void recordEvent(CommandEvent event, CorrectiveAction action, int learningMinEvents) {
        Objects.requireNonNull(action, "action must not be null");
        if (action == CorrectiveAction.ALLOW) {
            recordAllowedEvent(event, learningMinEvents);
        }
    }

    /**
     * Update and persist the user's Behavioral_Profile with an allowed Command_Event (Req 3.1, 3.2):
     * increments the command vocabulary, the {@code "prev>curr"} bigram, the category typed-vs-pasted
     * ratio, the timing patterns, and the category's context associations; increments the event
     * count; and stamps the learning state and update timestamp before persisting.
     *
     * <p>Callers that route decisions should prefer {@link #recordEvent} so the allowed-only policy
     * is enforced in one place.
     *
     * @param event             an allowed Command_Event
     * @param learningMinEvents the configured minimum event count for the ACTIVE state
     */
    public void recordAllowedEvent(CommandEvent event, int learningMinEvents) {
        Objects.requireNonNull(event, "event must not be null");
        String userId = event.userId();
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            BehavioralProfileDocument profile = repository.findByUserId(userId).orElseGet(() -> newProfile(userId));

            String executable = CommandNormalizer.executable(event.commandText());
            String token = CommandNormalizer.normalizedToken(event.commandText());
            String category = CommandNormalizer.category(event.commandText());

            if (!executable.isEmpty()) {
                merge(profile.getVocabulary(), executable);
            }
            if (!token.isEmpty()) {
                String previous = lastTokenByUser.get(userId);
                if (previous != null && !previous.isEmpty()) {
                    merge(profile.getSequenceStats(), previous + ">" + token);
                }
            }
            updateTypedPastedRatio(profile, category, event.inputOrigin());
            updateTiming(profile, userId, event.timestamp());
            updateContextAssociations(profile, category, event);

            long newCount = profile.getEventCount() + 1;
            profile.setEventCount(newCount);
            profile.setState(stateFor(newCount, learningMinEvents).name());
            profile.setUpdatedAt(clock.millis());

            repository.save(profile);

            // Advance continuity state only after a successful persist.
            if (!token.isEmpty()) {
                lastTokenByUser.put(userId, token);
            }
            lastTimestampByUser.put(userId, event.timestamp());
        } finally {
            lock.unlock();
        }
    }

    /**
     * The learning state of {@code userId}'s profile given the configured minimum (Req 3.3): a
     * profile with fewer than {@code learningMinEvents} recorded events (including a not-yet-created
     * profile) is {@link ProfileState#LEARNING}; otherwise {@link ProfileState#ACTIVE}. This is the
     * state the scoring path reports on each score.
     */
    public ProfileState profileStateFor(String userId, int learningMinEvents) {
        long eventCount = repository.findByUserId(userId)
                .map(BehavioralProfileDocument::getEventCount)
                .orElse(0L);
        return stateFor(eventCount, learningMinEvents);
    }

    /**
     * Adapt {@code userId}'s persisted profile into the read-only {@link ProfileSnapshot} the
     * deterministic scoring components consume. Returns {@link ProfileSnapshot#empty()} for a user
     * with no profile yet. The snapshot's {@code lastCommandToken} is the user's most recent allowed
     * command token, when known, so Sequence_Surprise can form the next bigram transition.
     */
    public ProfileSnapshot snapshotForUser(String userId) {
        Optional<BehavioralProfileDocument> found = repository.findByUserId(userId);
        if (found.isEmpty()) {
            return ProfileSnapshot.empty();
        }
        BehavioralProfileDocument profile = found.get();
        return ProfileSnapshot.builder()
                .eventCount(profile.getEventCount())
                .vocabulary(profile.getVocabulary())
                .sequenceStats(profile.getSequenceStats())
                .typedPastedRatioByCategory(profile.getTypedPastedRatioByCategory())
                .contextAssociations(profile.getContextAssociations())
                .lastCommandToken(lastTokenByUser.get(userId))
                .build();
    }

    /**
     * {@link ProfileSnapshotProvider} implementation used by the scoring components: resolves the
     * snapshot for the event's user, or {@link Optional#empty()} when the user has no profile yet.
     */
    @Override
    public Optional<ProfileSnapshot> snapshotFor(ScoringContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        String userId = ctx.event().userId();
        if (repository.findByUserId(userId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshotForUser(userId));
    }

    // --- internals ----------------------------------------------------------------------------

    private ProfileState stateFor(long eventCount, int learningMinEvents) {
        return eventCount < learningMinEvents ? ProfileState.LEARNING : ProfileState.ACTIVE;
    }

    private ReentrantLock lockFor(String userId) {
        return userLocks.computeIfAbsent(userId, key -> new ReentrantLock());
    }

    private BehavioralProfileDocument newProfile(String userId) {
        BehavioralProfileDocument profile = new BehavioralProfileDocument();
        profile.setUserId(userId);
        profile.setEventCount(0);
        profile.setState(ProfileState.LEARNING.name());
        profile.setVocabulary(new LinkedHashMap<>());
        profile.setSequenceStats(new LinkedHashMap<>());
        profile.setTypedPastedRatioByCategory(new LinkedHashMap<>());
        profile.setContextAssociations(new LinkedHashMap<>());
        TimingPatternsDocument timing = new TimingPatternsDocument();
        timing.setHourHistogram(newHourHistogram());
        timing.setMeanInterCommandMs(0);
        profile.setTimingPatterns(timing);
        return profile;
    }

    private static void merge(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private void updateTypedPastedRatio(BehavioralProfileDocument profile, String category, InputOrigin origin) {
        // Only TYPED/PASTED events inform the ratio; UNKNOWN origin leaves it unchanged (Req 2.4).
        double sample;
        if (origin == InputOrigin.TYPED) {
            sample = 1.0;
        } else if (origin == InputOrigin.PASTED) {
            sample = 0.0;
        } else {
            return;
        }
        Map<String, Double> ratios = profile.getTypedPastedRatioByCategory();
        Double existing = ratios.get(category);
        double updated = existing == null
                ? sample
                : (1.0 - RATIO_SMOOTHING) * existing + RATIO_SMOOTHING * sample;
        ratios.put(category, clampUnit(updated));
    }

    private void updateTiming(BehavioralProfileDocument profile, String userId, long timestamp) {
        TimingPatternsDocument timing = profile.getTimingPatterns();
        if (timing == null) {
            timing = new TimingPatternsDocument();
            profile.setTimingPatterns(timing);
        }
        List<Integer> histogram = timing.getHourHistogram();
        if (histogram == null || histogram.size() != HOURS_IN_DAY) {
            histogram = newHourHistogram();
            timing.setHourHistogram(histogram);
        }
        int hour = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).getHour();
        histogram.set(hour, histogram.get(hour) + 1);

        Long previousTs = lastTimestampByUser.get(userId);
        if (previousTs != null && timestamp >= previousTs) {
            long interval = timestamp - previousTs;
            long existingMean = timing.getMeanInterCommandMs();
            long updatedMean = existingMean <= 0
                    ? interval
                    : Math.round((1.0 - TIMING_SMOOTHING) * existingMean + TIMING_SMOOTHING * interval);
            timing.setMeanInterCommandMs(updatedMean);
        }
    }

    private void updateContextAssociations(
            BehavioralProfileDocument profile, String category, CommandEvent event) {
        Map<String, List<String>> associations = profile.getContextAssociations();
        List<String> tags = associations.computeIfAbsent(category, key -> new java.util.ArrayList<>());
        for (String tag : contextTagsFor(event)) {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
    }

    /**
     * Deterministically derives the context tags a command was observed in from its cwd/repo/env.
     * A command inside a repository is tagged {@link #TAG_REPO_DIR}; one under a home directory
     * {@link #TAG_HOME}; any other working directory {@link #TAG_WORKING_DIR}.
     */
    private static List<String> contextTagsFor(CommandEvent event) {
        List<String> tags = new java.util.ArrayList<>();
        if (isPresent(event.repo())) {
            tags.add(TAG_REPO_DIR);
        }
        String cwd = event.cwd();
        if (isPresent(cwd)) {
            if (looksLikeHome(cwd)) {
                tags.add(TAG_HOME);
            } else if (!isPresent(event.repo())) {
                tags.add(TAG_WORKING_DIR);
            }
        }
        return tags;
    }

    private static boolean looksLikeHome(String cwd) {
        String normalized = cwd.strip();
        return normalized.startsWith("~") || normalized.startsWith("/home/") || normalized.equals("/home")
                || normalized.startsWith("/Users/") || normalized.equals("/root") || normalized.startsWith("/root/");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static List<Integer> newHourHistogram() {
        List<Integer> histogram = new java.util.ArrayList<>(HOURS_IN_DAY);
        for (int i = 0; i < HOURS_IN_DAY; i++) {
            histogram.add(0);
        }
        return histogram;
    }

    private static double clampUnit(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
