package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Repository for the {@code snapshots} collection (Req 15.2). Each Snapshot is keyed by its
 * {@code eventId} and upserted, so re-capturing for the same Command_Event replaces the prior
 * metadata rather than duplicating it. Marking a Snapshot undone (Req 15.3) is a targeted update
 * of the {@code undone}/{@code undoneAt} fields.
 *
 * <p>Follows the same constructor-injected {@link MongoDatabase} pattern as the other repositories
 * in this package so it is registered as a Spring bean and participates in {@code contextLoads}.
 */
@Repository
public class SnapshotRepository {

    static final String COLLECTION = "snapshots";

    private final MongoCollection<SnapshotDocument> collection;

    public SnapshotRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, SnapshotDocument.class);
    }

    /** Upserts Snapshot undo metadata by {@code eventId} (Req 15.2). */
    public void save(SnapshotDocument snapshot) {
        collection.replaceOne(
                eq("eventId", snapshot.getEventId()),
                snapshot,
                new ReplaceOptions().upsert(true));
    }

    /** Looks up the Snapshot captured for a Command_Event, if any. */
    public Optional<SnapshotDocument> findByEventId(String eventId) {
        return Optional.ofNullable(collection.find(eq("eventId", eventId)).first());
    }

    /**
     * Marks the Snapshot for {@code eventId} as undone at {@code undoneAtMs} (Req 15.3). Returns the
     * updated document, or {@link Optional#empty()} if no Snapshot exists for the event.
     */
    public Optional<SnapshotDocument> markUndone(String eventId, long undoneAtMs) {
        Optional<SnapshotDocument> existing = findByEventId(eventId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        SnapshotDocument snapshot = existing.get();
        snapshot.setUndone(true);
        snapshot.setUndoneAt(undoneAtMs);
        save(snapshot);
        return Optional.of(snapshot);
    }
}
