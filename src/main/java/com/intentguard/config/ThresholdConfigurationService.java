package com.intentguard.config;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.intentguard.persistence.ThresholdConfigRepository;

import jakarta.annotation.PostConstruct;

/**
 * Holds the active Threshold_Configuration in memory and applies validated Administrator updates
 * without a restart (hot-reload; Req 7.1, 7.5).
 *
 * <p>The active configuration is served from an {@link AtomicReference} so that subsequent
 * Command_Events read the newest applied configuration with no restart. An update is validated by
 * materializing a {@link ThresholdConfiguration} (which is valid by construction): on success the
 * version is incremented, the configuration is persisted via {@link ThresholdConfigRepository}, and
 * the in-memory reference is swapped; on failure an {@link InvalidThresholdConfigException} is
 * thrown and the previously active configuration is retained unchanged (nothing is persisted).
 *
 * <p>Thread-safety: reads and the reference swap are atomic. Because updates are expected to arrive
 * from a single Administrator path but could race, the persist-then-swap is guarded by a monitor so
 * version numbers stay monotonic and the persisted document matches the in-memory reference.
 */
@Service
public class ThresholdConfigurationService {

    private static final Logger log = System.getLogger(ThresholdConfigurationService.class.getName());

    private final ThresholdConfigRepository repository;
    private final AtomicReference<ThresholdConfiguration> active = new AtomicReference<>();
    private final Object updateLock = new Object();
    private volatile Clock clock = Clock.systemUTC();

    public ThresholdConfigurationService(ThresholdConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * Test seam: overrides the clock used to stamp {@code updatedAt} on applied configurations so
     * that update timestamps are deterministic in tests.
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Loads the active configuration from the Datastore on startup, if one exists.
     *
     * <p>A transient Datastore outage at startup must not prevent the engine from starting: if the
     * read fails, it is logged and the active configuration is left empty, to be populated by a
     * later {@link #reloadFromDatastore()} or the first {@link #applyUpdate}.
     */
    @PostConstruct
    public void loadActive() {
        try {
            repository.findActive()
                    .map(ThresholdConfiguration::fromDocument)
                    .ifPresent(active::set);
        } catch (RuntimeException datastoreUnavailable) {
            log.log(Level.WARNING, "Could not load active Threshold_Configuration at startup; "
                    + "continuing without one until the Datastore is reachable", datastoreUnavailable);
        }
    }

    /** The active configuration currently in effect, if one has been loaded or applied. */
    public Optional<ThresholdConfiguration> getActiveConfig() {
        return Optional.ofNullable(active.get());
    }

    /**
     * Validates and applies an Administrator update. On success the new configuration takes effect
     * for subsequent Command_Events immediately (no restart), its version is one greater than the
     * currently active version (or 1 if none), and it is persisted.
     *
     * @throws InvalidThresholdConfigException if the proposed update is invalid; the previously
     *                                         active configuration is retained and nothing is persisted
     */
    public ThresholdConfiguration applyUpdate(ThresholdConfigUpdate update, String updatedBy) {
        synchronized (updateLock) {
            ThresholdConfiguration current = active.get();
            int nextVersion = current == null ? 1 : current.version() + 1;
            // Materializing the candidate validates it; an invalid update throws here, before any
            // persistence or reference swap, so the previous configuration is retained.
            ThresholdConfiguration candidate =
                    ThresholdConfiguration.fromUpdate(nextVersion, update, updatedBy, clock.millis());
            repository.save(candidate.toDocument());
            active.set(candidate);
            return candidate;
        }
    }

    /**
     * Seeds the active configuration directly (e.g. bootstrapping a first-run default), persisting
     * it and making it the in-memory active configuration. The provided configuration is already
     * validated by construction.
     */
    public ThresholdConfiguration initialize(ThresholdConfiguration config) {
        synchronized (updateLock) {
            repository.save(config.toDocument());
            active.set(config);
            return config;
        }
    }

    /**
     * Re-reads the active configuration from the Datastore and swaps it in if a newer version is
     * found there (supports hot-reload of updates applied out-of-process).
     */
    public Optional<ThresholdConfiguration> reloadFromDatastore() {
        synchronized (updateLock) {
            Optional<ThresholdConfiguration> reloaded =
                    repository.findActive().map(ThresholdConfiguration::fromDocument);
            reloaded.ifPresent(reloadedConfig -> {
                ThresholdConfiguration current = active.get();
                if (current == null || reloadedConfig.version() >= current.version()) {
                    active.set(reloadedConfig);
                }
            });
            return getActiveConfig();
        }
    }
}
