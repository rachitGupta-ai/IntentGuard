package com.intentguard.blastradius;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.intentguard.persistence.GuardrailConfigRepository;

import jakarta.annotation.PostConstruct;

/**
 * Holds the active {@link GuardrailConfig} in memory and applies validated Administrator updates
 * without a restart (hot-reload; Req 3.1, 9.5, 9.6).
 *
 * <p>Mirrors {@link com.intentguard.config.ThresholdConfigurationService}. The active configuration
 * is served from an {@link AtomicReference} so that subsequent guardrail evaluations read the newest
 * applied configuration with no restart. An update is validated by materializing a
 * {@link GuardrailConfig} (which is valid by construction): on success the version is incremented,
 * the configuration is persisted via {@link GuardrailConfigRepository}, and the in-memory reference
 * is swapped; on failure an {@link InvalidGuardrailConfigException} is thrown and the previously
 * active configuration is retained unchanged (last-known-good; nothing is persisted).
 *
 * <p>Thread-safety: reads and the reference swap are atomic. Because updates could race, the
 * persist-then-swap is guarded by a monitor so version numbers stay monotonic and the persisted
 * document matches the in-memory reference.
 */
@Service
public class GuardrailConfigService {

    private static final Logger log = System.getLogger(GuardrailConfigService.class.getName());

    private final GuardrailConfigRepository repository;
    private final AtomicReference<GuardrailConfig> active = new AtomicReference<>();
    private final Object updateLock = new Object();
    private volatile Clock clock = Clock.systemUTC();

    public GuardrailConfigService(GuardrailConfigRepository repository) {
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
                    .map(GuardrailConfig::fromDocument)
                    .ifPresent(active::set);
        } catch (RuntimeException datastoreUnavailable) {
            log.log(Level.WARNING, "Could not load active Guardrail_Configuration at startup; "
                    + "continuing without one until the Datastore is reachable", datastoreUnavailable);
        }
    }

    /**
     * Seeds and initializes the active configuration on startup. If a configuration already exists
     * in the Datastore it is loaded; otherwise the supplied default is persisted and made active,
     * so the guardrail layer always has a configuration to consult. Resilient to a transient
     * Datastore outage: on read failure the default is held in memory without persisting.
     */
    public void initialize(GuardrailConfig defaultConfig) {
        synchronized (updateLock) {
            try {
                Optional<GuardrailConfig> loaded =
                        repository.findActive().map(GuardrailConfig::fromDocument);
                if (loaded.isPresent()) {
                    active.set(loaded.get());
                    return;
                }
                repository.save(defaultConfig.toDocument());
                active.set(defaultConfig);
            } catch (RuntimeException datastoreUnavailable) {
                log.log(Level.WARNING, "Could not initialize Guardrail_Configuration from the "
                        + "Datastore; holding the default in memory until the Datastore is reachable",
                        datastoreUnavailable);
                active.set(defaultConfig);
            }
        }
    }

    /** The active configuration currently in effect, if one has been loaded or applied. */
    public Optional<GuardrailConfig> getActiveConfig() {
        return Optional.ofNullable(active.get());
    }

    /**
     * Validates and applies an Administrator update. On success the new configuration takes effect
     * for subsequent guardrail evaluations immediately (no restart), its version is one greater than
     * the currently active version (or 1 if none), and it is persisted.
     *
     * @throws InvalidGuardrailConfigException if the proposed update is invalid; the previously
     *                                         active configuration is retained and nothing is persisted
     */
    public GuardrailConfig applyUpdate(GuardrailConfigUpdate update, String updatedBy) {
        synchronized (updateLock) {
            GuardrailConfig current = active.get();
            int nextVersion = current == null ? 1 : current.version() + 1;
            // Materializing the candidate validates it; an invalid update throws here, before any
            // persistence or reference swap, so the previous configuration is retained.
            GuardrailConfig candidate = update.toConfig(nextVersion, updatedBy, clock.millis());
            repository.save(candidate.toDocument());
            active.set(candidate);
            return candidate;
        }
    }

    /**
     * Re-reads the active configuration from the Datastore and swaps it in if a newer version is
     * found there (supports hot-reload of updates applied out-of-process).
     */
    public Optional<GuardrailConfig> reloadFromDatastore() {
        synchronized (updateLock) {
            Optional<GuardrailConfig> reloaded =
                    repository.findActive().map(GuardrailConfig::fromDocument);
            reloaded.ifPresent(reloadedConfig -> {
                GuardrailConfig current = active.get();
                if (current == null || reloadedConfig.version() >= current.version()) {
                    active.set(reloadedConfig);
                }
            });
            return getActiveConfig();
        }
    }
}
