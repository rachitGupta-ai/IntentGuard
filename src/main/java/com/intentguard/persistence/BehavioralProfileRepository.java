package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Repository for the {@code behavioral_profiles} collection (Req 3.1, 3.5). Profiles are keyed by
 * {@code userId} and upserted in place.
 *
 * <p>Reads go through a {@link LastKnownGoodCache}: a successfully loaded profile is cached, and
 * on a transient Datastore read failure the last-known-good profile is served instead of failing
 * the scoring hot path. Writes refresh the cache so a following read reflects the latest state.
 */
@Repository
public class BehavioralProfileRepository {

    static final String COLLECTION = "behavioral_profiles";

    private final MongoCollection<BehavioralProfileDocument> collection;
    private final LastKnownGoodCache<String, BehavioralProfileDocument> cache = new LastKnownGoodCache<>();

    public BehavioralProfileRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, BehavioralProfileDocument.class);
    }

    /**
     * Loads a user's profile, caching the result and falling back to the last-known-good profile
     * on a transient read failure. Returns {@link Optional#empty()} when the user has no profile
     * yet (and none is cached).
     */
    public Optional<BehavioralProfileDocument> findByUserId(String userId) {
        BehavioralProfileDocument profile =
                cache.load(userId, () -> collection.find(eq("userId", userId)).first());
        return Optional.ofNullable(profile);
    }

    /** Upserts a profile by {@code userId} (Req 3.2, 3.5) and refreshes the cache. */
    public void save(BehavioralProfileDocument profile) {
        collection.replaceOne(
                eq("userId", profile.getUserId()), profile, new ReplaceOptions().upsert(true));
        cache.put(profile.getUserId(), profile);
    }
}
