package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code threshold_config} collection (Req 7.1, 7.5). Configurations are
 * versioned; the highest-versioned document is the active configuration.
 *
 * <p>The active configuration is read through a {@link LastKnownGoodCache} so that a transient
 * Datastore read failure does not leave the decision path without thresholds: the last-known-good
 * configuration is served instead. Writes refresh the cache.
 */
@Repository
public class ThresholdConfigRepository {

    static final String COLLECTION = "threshold_config";
    private static final String ACTIVE_KEY = "active";

    private final MongoCollection<ThresholdConfigDocument> collection;
    private final LastKnownGoodCache<String, ThresholdConfigDocument> cache = new LastKnownGoodCache<>();

    public ThresholdConfigRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, ThresholdConfigDocument.class);
    }

    /**
     * Returns the active (highest-versioned) configuration, caching it and falling back to the
     * last-known-good configuration on a transient read failure. Returns {@link Optional#empty()}
     * when no configuration exists yet (and none is cached).
     */
    public Optional<ThresholdConfigDocument> findActive() {
        ThresholdConfigDocument config =
                cache.load(ACTIVE_KEY, () -> collection.find().sort(Sorts.descending("version")).first());
        return Optional.ofNullable(config);
    }

    /** Looks up a specific configuration version. */
    public Optional<ThresholdConfigDocument> findByVersion(int version) {
        return Optional.ofNullable(collection.find(eq("version", version)).first());
    }

    /**
     * Upserts a configuration by {@code version} (Req 7.5). If the saved version is the newest, the
     * active-config cache is refreshed so subsequent reads reflect it.
     */
    public void save(ThresholdConfigDocument config) {
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
