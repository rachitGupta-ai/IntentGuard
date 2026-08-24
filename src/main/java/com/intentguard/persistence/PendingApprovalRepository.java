package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.intentguard.dualcontrol.ApprovalStatus;
import com.intentguard.dualcontrol.PendingApproval;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

/**
 * Repository for the {@code pending_approvals} collection (Req 4.9). Each DualControl approval is
 * keyed by {@code eventId} and upserted so that resolving an approval (confirming, rejecting, or
 * timing it out) updates the same document. Persisting approvals lets them survive Enforcement_Engine
 * restarts and keeps them auditable.
 *
 * <p>The stored shape is a {@link PendingApprovalDocument} JavaBean (POJO codec), mapped to and from
 * the {@link PendingApproval} domain type at the repository boundary. The {@link #findPending()}
 * query supports the DualControl timeout sweep (Req 4.5).
 */
@Repository
public class PendingApprovalRepository {

    static final String COLLECTION = "pending_approvals";

    private final MongoCollection<PendingApprovalDocument> collection;

    public PendingApprovalRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, PendingApprovalDocument.class);
    }

    /** Upserts a pending approval by {@code eventId} (Req 4.1, 4.9). */
    public void save(PendingApproval approval) {
        collection.replaceOne(
                eq("eventId", approval.eventId()),
                toDocument(approval),
                new ReplaceOptions().upsert(true));
    }

    /** Looks up an approval by the Command_Event id it gates. */
    public Optional<PendingApproval> findByEventId(String eventId) {
        return Optional.ofNullable(collection.find(eq("eventId", eventId)).first())
                .map(PendingApprovalRepository::toDomain);
    }

    /**
     * Returns all approvals still awaiting confirmation, ordered oldest-first, to drive the
     * DualControl timeout sweep (Req 4.5).
     */
    public List<PendingApproval> findPending() {
        return findByStatus(ApprovalStatus.PENDING);
    }

    /** Returns all approvals in the given status, ordered oldest-first. */
    public List<PendingApproval> findByStatus(ApprovalStatus status) {
        List<PendingApprovalDocument> docs = new ArrayList<>();
        collection.find(eq("status", status.name())).sort(Sorts.ascending("raisedAt")).into(docs);
        List<PendingApproval> results = new ArrayList<>(docs.size());
        for (PendingApprovalDocument doc : docs) {
            results.add(toDomain(doc));
        }
        return results;
    }

    static PendingApprovalDocument toDocument(PendingApproval approval) {
        PendingApprovalDocument doc = new PendingApprovalDocument();
        doc.setEventId(approval.eventId());
        doc.setRequesterId(approval.requesterId());
        doc.setApproverId(approval.approverId());
        doc.setStatus(approval.status() == null ? null : approval.status().name());
        doc.setStepUpRequired(approval.stepUpRequired());
        doc.setRaisedAt(approval.raisedAt());
        doc.setExpiresAt(approval.expiresAt());
        doc.setResolvedAt(approval.resolvedAt());
        return doc;
    }

    static PendingApproval toDomain(PendingApprovalDocument doc) {
        return new PendingApproval(
                doc.getEventId(),
                doc.getRequesterId(),
                doc.getApproverId(),
                doc.getStatus() == null ? null : ApprovalStatus.valueOf(doc.getStatus()),
                doc.isStepUpRequired(),
                doc.getRaisedAt(),
                doc.getExpiresAt(),
                doc.getResolvedAt());
    }
}
