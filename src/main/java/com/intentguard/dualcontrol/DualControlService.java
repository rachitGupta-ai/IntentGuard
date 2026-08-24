package com.intentguard.dualcontrol;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.domain.CommandEvent;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.PendingApprovalRepository;

/**
 * DualControl (four-eyes) and break-glass authorization service (Req 4).
 *
 * <p>When a high-risk or policy-flagged Command_Event requires a second Approver, the guardrail
 * chain {@linkplain #raisePending(CommandEvent, boolean) raises a pending approval} and withholds
 * execution until a <em>distinct</em> Approver {@linkplain #confirm(String, String, boolean)
 * confirms} it, or the confirmation {@linkplain #expireOverdue(long) times out}.
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@code raisePending} persists a {@link ApprovalStatus#PENDING} approval whose
 *       {@code expiresAt} is {@code now + } the configured
 *       {@code dualControlConfirmationTimeoutMs} (Req 4.1, 4.2, 4.5).</li>
 *   <li>{@code confirm} rejects self-approval (approver identity equal to the requesting Actor,
 *       Req 4.3) and rejects a failed/absent step-up when the approval required step-up (Req 4.7);
 *       in both cases the approval stays {@code PENDING} so a valid distinct Approver may still
 *       confirm. Otherwise it records the confirming Approver and marks the approval
 *       {@link ApprovalStatus#CONFIRMED} (Req 4.4). It is idempotent for already-resolved
 *       events.</li>
 *   <li>{@code expireOverdue} sweeps {@code PENDING} approvals past their {@code expiresAt} to
 *       {@link ApprovalStatus#TIMED_OUT} (⇒ {@code BLOCK}, Req 4.5).</li>
 * </ul>
 *
 * <p>Every raise and resolve is written to the Audit_History with record type
 * {@value #RECORD_TYPE_REQUEST} / {@value #RECORD_TYPE_RESOLVED} (Req 4.9). An injectable
 * {@link Clock} stamps {@code raisedAt} so timeouts are deterministic in tests.
 */
@Service
public class DualControlService {

    /** Audit record type written when a DualControl approval is raised (Req 4.9). */
    public static final String RECORD_TYPE_REQUEST = "DUAL_CONTROL_REQUEST";

    /** Audit record type written when a DualControl approval is resolved (Req 4.9). */
    public static final String RECORD_TYPE_RESOLVED = "DUAL_CONTROL_RESOLVED";

    /** Reason code for a raised, still-withheld approval (Req 4.1, 4.2). */
    public static final String REASON_PENDING = "DUAL_CONTROL_PENDING";

    /** Reason code for a confirmation by a distinct, successfully re-authenticated Approver (Req 4.4). */
    public static final String REASON_CONFIRMED = "DUAL_CONTROL_CONFIRMED";

    /** Reason code for a rejected self-approval; execution stays withheld (Req 4.3). */
    public static final String REASON_SELF_APPROVAL = "DUAL_CONTROL_SELF_APPROVAL_REJECTED";

    /** Reason code for a rejected confirmation due to failed/absent step-up re-auth (Req 4.7). */
    public static final String REASON_STEP_UP_FAILED = "DUAL_CONTROL_STEP_UP_FAILED";

    /** Reason code for an approval blocked by the confirmation timeout (Req 4.5). */
    public static final String REASON_TIMEOUT = "DUAL_CONTROL_TIMEOUT";

    /** Reason code for a confirmation attempt against an unknown event id. */
    public static final String REASON_UNKNOWN_EVENT = "DUAL_CONTROL_UNKNOWN_EVENT";

    private final PendingApprovalRepository approvals;
    private final AuditHistoryRepository auditHistory;
    private final GuardrailConfigService guardrailConfigService;
    private volatile Clock clock = Clock.systemUTC();

    public DualControlService(
            PendingApprovalRepository approvals,
            AuditHistoryRepository auditHistory,
            GuardrailConfigService guardrailConfigService) {
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.auditHistory = Objects.requireNonNull(auditHistory, "auditHistory must not be null");
        this.guardrailConfigService =
                Objects.requireNonNull(guardrailConfigService, "guardrailConfigService must not be null");
    }

    /**
     * Test seam: overrides the clock used to stamp {@code raisedAt}/{@code expiresAt} so approval
     * timing is deterministic in tests.
     */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Raises a {@link ApprovalStatus#PENDING} approval for {@code event}, withholding its execution
     * until a distinct Approver confirms or the confirmation timeout elapses (Req 4.1, 4.2). The
     * requester identity is the event's acting Actor; {@code expiresAt} is {@code now +} the
     * configured {@code dualControlConfirmationTimeoutMs} (Req 4.5). The raised request is persisted
     * and written to the Audit_History (Req 4.9).
     *
     * @param event          the Command_Event whose execution is withheld pending approval
     * @param stepUpRequired whether confirming this approval requires step-up re-authentication
     *                       (block-range actions, Req 4.6)
     * @return the persisted {@link PendingApproval} in the {@code PENDING} state
     */
    public PendingApproval raisePending(CommandEvent event, boolean stepUpRequired) {
        Objects.requireNonNull(event, "event must not be null");
        long now = clock.millis();
        long expiresAt = now + confirmationTimeoutMs();
        PendingApproval approval = new PendingApproval(
                event.eventId(),
                event.userId(),
                null,
                ApprovalStatus.PENDING,
                stepUpRequired,
                now,
                expiresAt,
                null);
        approvals.save(approval);
        recordRequest(approval);
        return approval;
    }

    /**
     * Confirms a pending approval on behalf of {@code approverId} (Req 4.3, 4.4, 4.7).
     *
     * <ul>
     *   <li>An unknown event id yields {@link ApprovalStatus#REJECTED} with reason
     *       {@value #REASON_UNKNOWN_EVENT}.</li>
     *   <li>An already-resolved event returns its resolved status unchanged (idempotent).</li>
     *   <li>Self-approval (approver equal to the requesting Actor) is rejected and execution stays
     *       withheld; the approval remains {@code PENDING} (Req 4.3).</li>
     *   <li>A failed/absent step-up on an approval that required step-up is rejected and execution
     *       stays withheld; the approval remains {@code PENDING} (Req 4.7).</li>
     *   <li>Otherwise the approval is marked {@link ApprovalStatus#CONFIRMED}, recording the
     *       confirming Approver (Req 4.4).</li>
     * </ul>
     *
     * @param eventId    the Command_Event id awaiting approval
     * @param approverId the confirming Approver identity
     * @param stepUpOk   whether step-up re-authentication succeeded
     * @return the {@link ApprovalResult} describing the outcome
     */
    public ApprovalResult confirm(String eventId, String approverId, boolean stepUpOk) {
        Optional<PendingApproval> found = approvals.findByEventId(eventId);
        if (found.isEmpty()) {
            return new ApprovalResult(ApprovalStatus.REJECTED, REASON_UNKNOWN_EVENT);
        }
        PendingApproval approval = found.get();

        // Idempotent for already-resolved events: return the resolved outcome without re-persisting.
        if (approval.status() != ApprovalStatus.PENDING) {
            return new ApprovalResult(approval.status(), reasonForResolved(approval.status()));
        }

        // Self-approval: reject the attempt but keep withholding (stays PENDING) (Req 4.3).
        if (Objects.equals(approverId, approval.requesterId())) {
            recordResolved(approval, approverId, RECORD_TYPE_RESOLVED, REASON_SELF_APPROVAL, clock.millis());
            return new ApprovalResult(ApprovalStatus.REJECTED, REASON_SELF_APPROVAL);
        }

        // Failed/absent step-up on an approval that required it: reject but keep withholding (Req 4.7).
        if (approval.stepUpRequired() && !stepUpOk) {
            recordResolved(approval, approverId, RECORD_TYPE_RESOLVED, REASON_STEP_UP_FAILED, clock.millis());
            return new ApprovalResult(ApprovalStatus.REJECTED, REASON_STEP_UP_FAILED);
        }

        // Distinct Approver with (any required) step-up satisfied: confirm and permit (Req 4.4).
        long resolvedAt = clock.millis();
        PendingApproval confirmed = new PendingApproval(
                approval.eventId(),
                approval.requesterId(),
                approverId,
                ApprovalStatus.CONFIRMED,
                approval.stepUpRequired(),
                approval.raisedAt(),
                approval.expiresAt(),
                resolvedAt);
        approvals.save(confirmed);
        recordResolved(confirmed, approverId, RECORD_TYPE_RESOLVED, REASON_CONFIRMED, resolvedAt);
        return new ApprovalResult(ApprovalStatus.CONFIRMED, REASON_CONFIRMED);
    }

    /**
     * Sweeps every {@link ApprovalStatus#PENDING} approval whose {@code expiresAt} is strictly
     * before {@code nowMs}, marking it {@link ApprovalStatus#TIMED_OUT} (⇒ {@code BLOCK}),
     * persisting the transition, and writing it to the Audit_History (Req 4.5, 4.9).
     *
     * @param nowMs the current time in UTC epoch milliseconds
     * @return the approvals that transitioned to {@code TIMED_OUT} in this sweep
     */
    public List<PendingApproval> expireOverdue(long nowMs) {
        List<PendingApproval> timedOut = new ArrayList<>();
        for (PendingApproval approval : approvals.findPending()) {
            if (nowMs > approval.expiresAt()) {
                PendingApproval expired = new PendingApproval(
                        approval.eventId(),
                        approval.requesterId(),
                        approval.approverId(),
                        ApprovalStatus.TIMED_OUT,
                        approval.stepUpRequired(),
                        approval.raisedAt(),
                        approval.expiresAt(),
                        nowMs);
                approvals.save(expired);
                recordResolved(expired, null, RECORD_TYPE_RESOLVED, REASON_TIMEOUT, nowMs);
                timedOut.add(expired);
            }
        }
        return timedOut;
    }

    /** Looks up the current approval for {@code eventId}, if any. */
    public Optional<PendingApproval> find(String eventId) {
        return approvals.findByEventId(eventId);
    }

    /**
     * Returns whether an {@code AGENT} {@code event}'s command class lies within its configured
     * capability scope, per the active {@link GuardrailConfig} (Req 4.8). Human events and agents
     * without a configured scope are treated as within scope. Delegates to {@link CapabilityScope}.
     */
    public boolean withinCapabilityScope(CommandEvent event) {
        return CapabilityScope.isWithinScope(event, activeConfig()
                .map(GuardrailConfig::capabilityScopes)
                .orElse(null));
    }

    private long confirmationTimeoutMs() {
        return activeConfig()
                .map(GuardrailConfig::dualControlConfirmationTimeoutMs)
                .orElse(GuardrailConfig.DEFAULT_DUAL_CONTROL_CONFIRMATION_TIMEOUT_MS);
    }

    private Optional<GuardrailConfig> activeConfig() {
        return guardrailConfigService.getActiveConfig();
    }

    private static String reasonForResolved(ApprovalStatus status) {
        return switch (status) {
            case CONFIRMED -> REASON_CONFIRMED;
            case TIMED_OUT -> REASON_TIMEOUT;
            case REJECTED -> REASON_SELF_APPROVAL;
            case PENDING -> REASON_PENDING;
        };
    }

    private void recordRequest(PendingApproval approval) {
        AuditHistoryDocument record = baseRecord(approval, approval.raisedAt());
        record.setRecordType(RECORD_TYPE_REQUEST);
        record.setReasonCode(REASON_PENDING);
        record.setExplanation("DualControl approval requested for event " + approval.eventId()
                + "; execution withheld pending a distinct Approver.");
        auditHistory.save(record);
    }

    private void recordResolved(
            PendingApproval approval, String approverId, String recordType, String reasonCode, long timestamp) {
        AuditHistoryDocument record = baseRecord(approval, timestamp);
        record.setRecordType(recordType);
        record.setReasonCode(reasonCode);
        record.setHumanPrincipalId(approverId);
        record.setExplanation("DualControl approval for event " + approval.eventId()
                + " resolved: " + reasonCode
                + (approverId == null ? "" : " (approver=" + approverId + ")"));
        auditHistory.save(record);
    }

    private static AuditHistoryDocument baseRecord(PendingApproval approval, long timestamp) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(approval.eventId());
        record.setUserId(approval.requesterId());
        record.setTimestamp(timestamp);
        return record;
    }
}
