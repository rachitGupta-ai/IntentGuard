package com.intentguard.decision;

/**
 * The dual-control state of a Command_Event as seen by the {@link GuardrailDecisionEngine} chain
 * (Req 4). This is the guardrail-facing view carried on the {@link GuardrailContext}; the full
 * pending-approval lifecycle lives in the {@code com.intentguard.dualcontrol} package and is wired
 * into the chain in a later task.
 *
 * <ul>
 *   <li>{@code NONE} - no dual-control requirement applies to this Command_Event.</li>
 *   <li>{@code PENDING} - a dual-control confirmation has been raised and is awaiting a distinct
 *       Approver; execution is withheld (handled by the dual-control stage).</li>
 *   <li>{@code CONFIRMED} - a distinct Approver has confirmed and the event may proceed.</li>
 *   <li>{@code TIMED_OUT} - the confirmation window elapsed without a distinct Approver, which the
 *       chain resolves to a {@code BLOCK} (Req 4.5).</li>
 * </ul>
 */
public enum DualControlStatus {
    NONE,
    PENDING,
    CONFIRMED,
    TIMED_OUT
}
