package com.intentguard.dualcontrol;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.dualcontrol.DualControlTestSupport.InMemoryPendingApprovalRepository;
import com.intentguard.dualcontrol.DualControlTestSupport.RecordingAuditHistoryRepository;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;

/**
 * Feature: intentguard-guardrails, Property 18: Dual-control withholds until a distinct approver
 * confirms and rejects self-approval.
 *
 * <p>For any Command_Event whose Divergence_Score falls in the block range or that is matched by a
 * dual-control PolicyRule, a PENDING approval is raised and execution is withheld; a confirmation
 * whose approver identity equals the requesting Actor is rejected and execution stays withheld; and
 * a confirmation by a distinct Approver (with step-up satisfied where required) transitions to
 * CONFIRMED, permits the event to proceed, and records the confirming Approver identity
 * (Validates: Requirements 4.1, 4.2, 4.3, 4.4).
 */
class DualControlLifecycleProperties {

    @Property(tries = 200)
    void withholdsRejectsSelfApprovalAndConfirmsByDistinctApprover(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String requester,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String approverSuffix,
            @ForAll boolean stepUpRequired) {

        // Ensure the Approver identity is distinct from the requesting Actor.
        String approver = requester + "-approver-" + approverSuffix;

        InMemoryPendingApprovalRepository approvals = new InMemoryPendingApprovalRepository();
        RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
        long now = 1_000_000L;
        DualControlService service = DualControlTestSupport.service(
                approvals, audit, DualControlTestSupport.config(DualControlTestSupport.TIMEOUT_MS, Map.of()), now);

        CommandEvent event = event("evt", requester, now);

        // Raise: a PENDING approval is created and execution is withheld.
        PendingApproval raised = service.raisePending(event, stepUpRequired);
        assertThat(raised.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(raised.requesterId()).isEqualTo(requester);
        assertThat(raised.approverId()).isNull();
        assertThat(service.find("evt").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        // Self-approval: rejected, and the approval remains PENDING (still withheld).
        ApprovalResult selfApproval = service.confirm("evt", requester, true);
        assertThat(selfApproval.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(selfApproval.reasonCode()).isEqualTo(DualControlService.REASON_SELF_APPROVAL);
        assertThat(service.find("evt").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        // Distinct Approver, with step-up satisfied where required: CONFIRMED and Approver recorded.
        ApprovalResult confirmed = service.confirm("evt", approver, stepUpRequired);
        assertThat(confirmed.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(confirmed.reasonCode()).isEqualTo(DualControlService.REASON_CONFIRMED);
        PendingApproval resolved = service.find("evt").orElseThrow();
        assertThat(resolved.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(resolved.approverId()).isEqualTo(approver);
    }

    private static CommandEvent event(String eventId, String requester, long now) {
        return DualControlTestSupport.event(eventId, Actor.human(requester), "rm -rf /data", now);
    }
}
