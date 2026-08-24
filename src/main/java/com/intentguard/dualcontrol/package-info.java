/**
 * DualControl (four-eyes) authorization for high-risk and policy-flagged Command_Events
 * (Req 4). Defines the pending-approval domain model ({@link com.intentguard.dualcontrol.PendingApproval}
 * and its {@link com.intentguard.dualcontrol.ApprovalStatus} lifecycle) used to withhold execution
 * until a distinct Approver confirms or the confirmation timeout elapses.
 *
 * <p>Pending approvals are persisted to the {@code pending_approvals} collection via
 * {@link com.intentguard.persistence.PendingApprovalRepository} so they survive restarts and remain
 * auditable.
 */
package com.intentguard.dualcontrol;
