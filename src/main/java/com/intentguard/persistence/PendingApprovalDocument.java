package com.intentguard.persistence;

/**
 * Persisted DualControl pending approval for the {@code pending_approvals} collection (Req 4.9).
 * A document is written when a DualControl approval is raised and updated when it is resolved
 * (confirmed, rejected, or timed out), so approvals survive Enforcement_Engine restarts and are
 * auditable.
 *
 * <p>{@link #eventId} is the business key. The status is stored as its {@code name()} string to
 * keep the POJO codec mapping simple and forward-compatible; timestamps are UTC epoch millis.
 * {@link #approverId} is {@code null} until a distinct Approver confirms, and {@link #resolvedAt}
 * is {@code null} while the approval is still pending.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class PendingApprovalDocument {

    private String eventId;
    private String requesterId;
    private String approverId;
    private String status;
    private boolean stepUpRequired;
    private long raisedAt;
    private long expiresAt;
    private Long resolvedAt;

    public PendingApprovalDocument() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getApproverId() {
        return approverId;
    }

    public void setApproverId(String approverId) {
        this.approverId = approverId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isStepUpRequired() {
        return stepUpRequired;
    }

    public void setStepUpRequired(boolean stepUpRequired) {
        this.stepUpRequired = stepUpRequired;
    }

    public long getRaisedAt() {
        return raisedAt;
    }

    public void setRaisedAt(long raisedAt) {
        this.raisedAt = raisedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
