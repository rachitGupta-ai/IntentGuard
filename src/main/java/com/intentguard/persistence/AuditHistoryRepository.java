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
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code audit_history} collection (Req 11.1, 11.2, 11.3). Every corrective
 * decision, rejected-tamper attempt, monitoring-gap/resumed event, and session anomaly is
 * persisted here as an {@link AuditHistoryDocument} and survives restarts.
 *
 * <p>Audit records are append-only in normal operation, so no in-memory caching is applied: reads
 * are historical queries rather than hot-path config/profile lookups.
 */
@Repository
public class AuditHistoryRepository {

    static final String COLLECTION = "audit_history";

    private final MongoCollection<AuditHistoryDocument> collection;

    public AuditHistoryRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, AuditHistoryDocument.class);
    }

    /** Persists an audit record (Req 11.1). */
    public void save(AuditHistoryDocument record) {
        collection.insertOne(record);
    }

    /** Looks up a single audit record by its event id. */
    public Optional<AuditHistoryDocument> findByEventId(String eventId) {
        return Optional.ofNullable(collection.find(eq("eventId", eventId)).first());
    }

    /**
     * Returns the audit records for a user whose timestamp falls within {@code [fromMs, toMs]}
     * inclusive, ordered oldest-first (Req 11.3).
     */
    public List<AuditHistoryDocument> queryByUserAndTimeRange(String userId, long fromMs, long toMs) {
        Bson filter = and(eq("userId", userId), gte("timestamp", fromMs), lte("timestamp", toMs));
        List<AuditHistoryDocument> results = new ArrayList<>();
        collection.find(filter).sort(Sorts.ascending("timestamp")).into(results);
        return results;
    }

    /** Returns all audit records, ordered oldest-first. */
    public List<AuditHistoryDocument> findAll() {
        List<AuditHistoryDocument> results = new ArrayList<>();
        collection.find().sort(Sorts.ascending("timestamp")).into(results);
        return results;
    }

    /**
     * Returns the audit records across <em>all</em> users whose timestamp is at or after
     * {@code sinceMs}, ordered oldest-first. Used to hydrate the Control_Tower dashboard on a fresh
     * page load so historical decisions/alerts from the last N days are shown without waiting for
     * new live events.
     */
    public List<AuditHistoryDocument> findSince(long sinceMs, int limit) {
        List<AuditHistoryDocument> results = new ArrayList<>();
        collection.find(gte("timestamp", sinceMs))
                .sort(Sorts.ascending("timestamp"))
                .limit(limit)
                .into(results);
        return results;
    }
}
