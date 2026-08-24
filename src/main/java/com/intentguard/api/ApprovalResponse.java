package com.intentguard.api;

/**
 * Response returned after an Approver attempts to confirm a pending DualControl approval from the
 * Control_Tower (Req 4.4, 4.6, 4.7). Echoes the event that was acted on, the resulting {@code
 * status} (for example {@code CONFIRMED} or {@code REJECTED}), the stable {@code reasonCode}
 * explaining the outcome, and the {@code approverId} that attempted the confirmation.
 *
 * <p>A non-{@code CONFIRMED} status (self-approval, failed step-up, or unknown event) is returned
 * with a 4xx status and the event remains withheld pending a valid distinct Approver.
 */
public record ApprovalResponse(String eventId, String status, String reasonCode, String approverId) {
}
