package com.intentguard.dualcontrol;

/**
 * A pending DualControl (four-eyes) approval for a high-risk or policy-flagged Command_Event
 * (Req 4.1, 4.2, 4.5, 4.9). While an approval is {@link ApprovalStatus#PENDING}, execution of the
 * associated Command_Event is withheld until a distinct Approver confirms it or the confirmation
 * timeout elapses.
 *
 * <p>Immutable value type. {@link #approverId} is {@code null} until a distinct Approver confirms
 * (Req 4.4), and {@link #resolvedAt} is {@code null} while the approval is still pending.
 * Timestamps are UTC epoch milliseconds.
 *
 * @param eventId        business key of the Command_Event awaiting approval
 * @param requesterId    identity of the requesting Actor
 * @param approverId     identity of the confirming Approver, or {@code null} until confirmed
 * @param status         current {@link ApprovalStatus}
 * @param stepUpRequired whether step-up re-authentication is required to confirm (block-range
 *                       actions, Req 4.6)
 * @param raisedAt       epoch-millis instant the approval was raised
 * @param expiresAt      epoch-millis instant after which an unconfirmed approval times out
 *                       ({@code raisedAt + confirmationTimeout}, Req 4.5)
 * @param resolvedAt     epoch-millis instant the approval was resolved, or {@code null} while
 *                       pending
 */
public record PendingApproval(
        String eventId,
        String requesterId,
        String approverId,
        ApprovalStatus status,
        boolean stepUpRequired,
        long raisedAt,
        long expiresAt,
        Long resolvedAt) {
}
