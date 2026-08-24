package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code guardrail_config} collection (Req 3.1, Req 4). Configurations are
 * versioned; the highest-versioned document is the active configuration.
 *
 * <p>Mirrors {@link ThresholdConfigRepository}: the active configuration is read through a
 * {@link LastKnownGoodCache} so that a transient Datastore read failure does not leave the guardrail
 * path without configuration — the last-known-good configuration is served instead. Writes refresh
 * the cache.
 */
@Repository
public class GuardrailConfigRepository {

    static final String COLLECTION = "guardrail_config";
    private static final String ACTIVE_KEY = "active";

    private final MongoCollection<GuardrailConfigDocument> collection;
    private final LastKnownGoodCache<String, GuardrailConfigDocument> cache = new LastKnownGoodCache<>();

    public GuardrailConfigRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, GuardrailConfigDocument.class);
    }

    /**
     * Returns the active (highest-versioned) configuration, caching it and falling back to the
     * last-known-good configuration on a transient read failure. Returns {@link Optional#empty()}
     * when no configuration exists yet (and none is cached).
     */
    public Optional<GuardrailConfigDocument> findActive() {
        GuardrailConfigDocument config =
                cache.load(ACTIVE_KEY, () -> collection.find().sort(Sorts.descending("version")).first());
        return Optional.ofNullable(config);
    }

    /** Looks up a specific configuration version. */
    public Optional<GuardrailConfigDocument> findByVersion(int version) {
        return Optional.ofNullable(collection.find(eq("version", version)).first());
    }

    /**
     * Upserts a configuration by {@code version}. If the saved version is the newest, the
     * active-config cache is refreshed so subsequent reads reflect it.
     */
    public void save(GuardrailConfigDocument config) {
        collection.replaceOne(
                eq("version", config.getVersion()), config, new ReplaceOptions().upsert(true));
        boolean newerThanCached = cache.peek(ACTIVE_KEY)
                .map(current -> config.getVersion() >= current.getVersion())
                .orElse(true);
        if (newerThanCached) {
            cache.put(ACTIVE_KEY, config);
        }
    }
}
