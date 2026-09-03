package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.conversions.Bson;
import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code intent_sessions} collection (Req 4.1, 4.5). Sessions are keyed by
 * {@code sessionId} and upserted so that closing a session (recording {@code endedAt} and setting
 * {@code open=false}) updates the same document.
 *
 * <p>Read-only additions for the User_Profile_Api (Req 1.1, 3.1, 7.4, 9.3) are pure reads that
 * never insert, update, or delete any document.
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
    public List<IntentSessionDocument> findRecentOrOpen(long sinceMs) {
        List<IntentSessionDocument> results = new ArrayList<>();
        collection.find(or(eq("open", true), gte("startedAt", sinceMs)))
                .sort(Sorts.ascending("startedAt"))
                .into(results);
        return results;
    }

    // -----------------------------------------------------------------------------------------
    // Read-only methods for the User_Profile_Api (Req 1.1, 3.1, 7.4, 9.3)
    // -----------------------------------------------------------------------------------------

    /**
     * Returns all sessions for the given {@code userId} whose {@code startedAt} timestamp falls
     * within {@code [from, to]} inclusive, ordered oldest-first (Req 3.1, 9.3).
     *
     * <p>Pure read — performs no insert, update, or delete.
     *
     * @param userId the user identifier to filter by
     * @param from   window lower bound, epoch-milliseconds inclusive
     * @param to     window upper bound, epoch-milliseconds inclusive
     * @return matching sessions ordered by {@code startedAt} ascending; never {@code null}
     */
    public List<IntentSessionDocument> findByUserIdAndTimeRange(String userId, long from, long to) {
        Bson filter = and(eq("userId", userId), gte("startedAt", from), lte("startedAt", to));
        List<IntentSessionDocument> results = new ArrayList<>();
        collection.find(filter).sort(Sorts.ascending("startedAt")).into(results);
        return results;
    }

    /**
     * Returns all distinct, non-null {@code userId} values present in the {@code intent_sessions}
     * collection (Req 1.1, 9.3). Used to compose the Known_User list.
     *
     * <p>Pure read — performs no insert, update, or delete.
     *
     * @return list of distinct user ids; never {@code null}; order is unspecified
     */
    public List<String> distinctUserIds() {
        List<String> results = new ArrayList<>();
        collection.distinct("userId", ne("userId", null), String.class).into(results);
        return results;
    }

    /**
     * Returns the earliest {@code startedAt} epoch-millisecond timestamp for the given
     * {@code userId}, or {@link Optional#empty()} when the user has no sessions (Req 7.4, 9.3).
     * Used to compute the full-history Active_Window lower bound.
     *
     * <p>Pure read — performs no insert, update, or delete.
     *
     * @param userId the user identifier to query
     * @return earliest {@code startedAt} value, or empty when no sessions exist for the user
     */
    public Optional<Long> earliestStartedAtForUser(String userId) {
        IntentSessionDocument earliest = collection
                .find(eq("userId", userId))
                .sort(Sorts.ascending("startedAt"))
                .limit(1)
                .first();
        return earliest == null ? Optional.empty() : Optional.of(earliest.getStartedAt());
    }
}
