package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.or;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code intent_sessions} collection (Req 4.1, 4.5). Sessions are keyed by
 * {@code sessionId} and upserted so that closing a session (recording {@code endedAt} and setting
 * {@code open=false}) updates the same document.
 */
@Repository
public class IntentSessionRepository {

    static final String COLLECTION = "intent_sessions";

    private final MongoCollection<IntentSessionDocument> collection;

    public IntentSessionRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, IntentSessionDocument.class);
    }

    /** Upserts a session by {@code sessionId} (Req 4.1, 4.3, 4.5). */
    public void save(IntentSessionDocument session) {
        collection.replaceOne(
                eq("sessionId", session.getSessionId()), session, new ReplaceOptions().upsert(true));
    }

    /** Looks up a session by its id. */
    public Optional<IntentSessionDocument> findBySessionId(String sessionId) {
        return Optional.ofNullable(collection.find(eq("sessionId", sessionId)).first());
    }

    /** Returns the currently open session for a user, if any (Req 4.2). */
    public Optional<IntentSessionDocument> findOpenByUserId(String userId) {
        return Optional.ofNullable(
                collection.find(and(eq("userId", userId), eq("open", true))).first());
    }

    /**
     * Returns sessions that are either still open or were started at/after {@code sinceMs},
     * ordered oldest-first. Used to hydrate the Control_Tower "active sessions" panel on a fresh
     * page load from persisted state.
     */
    public java.util.List<IntentSessionDocument> findRecentOrOpen(long sinceMs) {
        java.util.List<IntentSessionDocument> results = new java.util.ArrayList<>();
        collection.find(or(eq("open", true), gte("startedAt", sinceMs)))
                .sort(Sorts.ascending("startedAt"))
                .into(results);
        return results;
    }
}
