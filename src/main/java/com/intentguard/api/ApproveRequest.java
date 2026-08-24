package com.intentguard.api;

/**
 * Request body for a distinct Approver confirming a pending DualControl (four-eyes) approval from
 * the Control_Tower (Req 4.4, 4.7).
 *
 * <p>{@code approverId} identifies the confirming Approver, which MUST differ from the requesting
 * Actor — a self-approval is rejected and the event stays withheld (Req 4.3). {@code
 * stepUpAuthenticated} reports whether step-up re-authentication succeeded; a block-range approval
 * that required step-up is rejected (and stays withheld) unless this is {@code true} (Req 4.6,
 * 4.7).
 */
public record ApproveRequest(String approverId, boolean stepUpAuthenticated) {
}
