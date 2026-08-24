package com.intentguard.dualcontrol;

/**
 * Outcome of a DualControl confirmation attempt or timeout sweep (Req 4.3, 4.4, 4.5, 4.7).
 *
 * <p>The {@link #status} describes the result of the operation as applied to the pending approval,
 * and {@link #reasonCode} is a stable, machine-readable code (also written to the Audit_History)
 * naming <em>why</em> that outcome was reached.
 *
 * <p>Note that a {@link ApprovalStatus#REJECTED} result (self-approval or failed step-up) is the
 * outcome of a single confirmation <em>attempt</em>; the underlying {@link PendingApproval} remains
 * {@link ApprovalStatus#PENDING} so that a later distinct Approver with a successful step-up may
 * still confirm it before it times out (Req 4.3, 4.7).
 *
 * @param status     the resulting {@link ApprovalStatus} of the operation
 * @param reasonCode the stable reason code explaining the outcome
 */
public record ApprovalResult(ApprovalStatus status, String reasonCode) {
}
