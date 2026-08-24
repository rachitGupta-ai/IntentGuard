package com.intentguard.policy;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.intentguard.domain.CommandEvent;
import com.intentguard.persistence.CommandPolicyRepository;

import jakarta.annotation.PostConstruct;

/**
 * Holds the active {@link CommandPolicy} in memory and applies validated Administrator updates
 * without a restart (hot-reload; Req 2.2, 2.13, 2.14). Mirrors
 * {@link com.intentguard.config.ThresholdConfigurationService} exactly.
 *
 * <p>The active policy is served from an {@link AtomicReference} so that subsequent Command_Events
 * are evaluated against the newest applied policy with no restart (Req 2.2). An update is validated
 * by materializing a {@link CommandPolicy} (which is valid by construction): on success the version
 * is incremented, the policy is persisted via {@link CommandPolicyRepository}, and the in-memory
 * reference is swapped; on failure an {@link InvalidCommandPolicyException} is thrown and the
 * previously active policy is retained unchanged (nothing is persisted) (Req 2.13).
 *
 * <p>{@link #evaluate(CommandEvent)} normalizes the command text and arguments (delegated to
 * {@link PolicyRule}) and runs first-match over the active policy's rules in list order to produce
 * a {@link PolicyDecision} (Req 2.3, 2.4, 2.6).
 *
 * <p>Thread-safety: reads and the reference swap are atomic. The persist-then-swap is guarded by a
 * monitor so version numbers stay monotonic and the persisted policy matches the in-memory
 * reference.
 */
@Service
public class CommandPolicyService {

    private static final Logger log = System.getLogger(CommandPolicyService.class.getName());

    private final CommandPolicyRepository repository;
    private final AtomicReference<CommandPolicy> active = new AtomicReference<>();
    private final Object updateLock = new Object();
    private volatile Clock clock = Clock.systemUTC();

    public CommandPolicyService(CommandPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Test seam: overrides the clock used to stamp {@code updatedAt} on applied policies so update
     * timestamps are deterministic in tests.
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Loads the active policy from the Datastore on startup, if one exists.
     *
     * <p>A transient Datastore outage at startup must not prevent the engine from starting: if the
     * read fails, it is logged and the active policy is left empty, to be populated by a later
     * {@link #reloadFromDatastore()} or the first {@link #applyUpdate}.
     */
    @PostConstruct
    public void loadActive() {
        try {
            repository.findActive().ifPresent(active::set);
        } catch (RuntimeException datastoreUnavailable) {
            log.log(Level.WARNING, "Could not load active CommandPolicy at startup; continuing "
                    + "without one until the Datastore is reachable", datastoreUnavailable);
        }
    }

    /** The active policy currently in effect, if one has been loaded or applied (Req 2.14). */
    public Optional<CommandPolicy> getActivePolicy() {
        return Optional.ofNullable(active.get());
    }

    /**
     * Validates and applies an Administrator update. On success the new policy takes effect for
     * subsequent Command_Events immediately (no restart), its version is one greater than the
     * currently active version (or 1 if none), and it is persisted (Req 2.2, 2.14).
     *
     * @throws InvalidCommandPolicyException if the proposed update is invalid; the previously active
     *                                       policy is retained and nothing is persisted (Req 2.13)
     */
    public CommandPolicy applyUpdate(CommandPolicyUpdate update, String updatedBy) {
        synchronized (updateLock) {
            CommandPolicy current = active.get();
            int nextVersion = current == null ? 1 : current.version() + 1;
            // Materializing the candidate validates it; an invalid update throws here, before any
            // persistence or reference swap, so the previous policy is retained.
            CommandPolicy candidate =
                    new CommandPolicy(nextVersion, update.rules(), updatedBy, clock.millis());
            repository.save(candidate);
            active.set(candidate);
            return candidate;
        }
    }

    /**
     * Seeds the active policy directly (e.g. bootstrapping a first-run default), persisting it and
     * making it the in-memory active policy. The provided policy is already validated by
     * construction.
     */
    public CommandPolicy initialize(CommandPolicy policy) {
        synchronized (updateLock) {
            repository.save(policy);
            active.set(policy);
            return policy;
        }
    }

    /**
     * Re-reads the active policy from the Datastore and swaps it in if a newer version is found
     * there (supports hot-reload of updates applied out-of-process and reload across restarts;
     * Req 2.14).
     */
    public Optional<CommandPolicy> reloadFromDatastore() {
        synchronized (updateLock) {
            Optional<CommandPolicy> reloaded = repository.findActive();
            reloaded.ifPresent(reloadedPolicy -> {
                CommandPolicy current = active.get();
                if (current == null || reloadedPolicy.version() >= current.version()) {
                    active.set(reloadedPolicy);
                }
            });
            return getActivePolicy();
        }
    }

    /**
     * Evaluates the active CommandPolicy against one Command_Event, returning the first matching
     * rule as a {@link PolicyDecision}, or {@link PolicyDecision#none()} when no policy is active or
     * no rule matches (Req 2.3, 2.4, 2.6).
     *
     * <p>Matching normalizes the command text and arguments and applies each rule's scope and
     * pattern in list order (handled by {@link CommandPolicy#firstMatch}). The group facet is not
     * carried on the Command_Event or its {@link com.intentguard.domain.Actor}, so {@code null} is
     * passed for the group — group-scoped rules therefore match only when their group facet is also
     * unset; this is derived by {@link #groupOf(CommandEvent)} so the derivation is documented in a
     * single place and can be enriched later without changing callers.
     */
    public PolicyDecision evaluate(CommandEvent event) {
        CommandPolicy policy = active.get();
        if (policy == null) {
            return PolicyDecision.none();
        }
        return policy.firstMatch(event, groupOf(event))
                .map(PolicyDecision::of)
                .orElseGet(PolicyDecision::none);
    }

    /**
     * Derives the group a Command_Event is evaluated under for scope matching. The current domain
     * model does not carry a group on the {@link com.intentguard.domain.Actor} or the
     * {@link CommandEvent}, so this returns {@code null} ("any group unknown"). It is factored out
     * so a future group source can be wired in one place.
     */
    private static String groupOf(CommandEvent event) {
        return null;
    }
}
