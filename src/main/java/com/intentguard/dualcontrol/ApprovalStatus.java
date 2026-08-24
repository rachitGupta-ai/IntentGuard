package com.intentguard.dualcontrol;

/**
 * Lifecycle status of a {@link PendingApproval} for a DualControl (four-eyes) Command_Event
 * (Req 4.1–4.5).
 *
 * <ul>
 *   <li>{@link #PENDING} — a second-Approver confirmation is required and execution is withheld.
 *   <li>{@link #CONFIRMED} — a distinct Approver confirmed the event, which may now proceed.
 *   <li>{@link #REJECTED} — the confirmation was rejected (for example self-approval or failed
 *       step-up re-authentication) and execution remains withheld.
 *   <li>{@link #TIMED_OUT} — no distinct Approver confirmed within the confirmation timeout, so the
 *       event is blocked.
 * </ul>
 */
public enum ApprovalStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    TIMED_OUT
}
