package com.intentguard.dualcontrol;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.CommandEvent;
import com.intentguard.dualcontrol.DualControlTestSupport.InMemoryPendingApprovalRepository;
import com.intentguard.dualcontrol.DualControlTestSupport.RecordingAuditHistoryRepository;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;

/**
 * Feature: intentguard-guardrails, Property 20: Block-range confirmations require successful step-up
 * re-authentication.
 *
 * <p>For any block-range pending dual-control approval, a confirmation that lacks successful step-up
 * re-authentication is rejected, no CONFIRMED state is recorded, and execution continues to be
 * withheld; only a confirmation with successful step-up (by a distinct Approver) is recorded
 * (Validates: Requirements 4.6, 4.7).
 */
class DualControlStepUpProperties {

    @Property(tries = 200)
    void stepUpRequiredRejectsFailedStepUpAndOnlyRecordsSuccessfulStepUp(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String requester,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String approverSuffix) {

        String approver = requester + "-approver-" + approverSuffix;

        InMemoryPendingApprovalRepository approvals = new InMemoryPendingApprovalRepository();
        RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
        long now = 1_000_000L;
        DualControlService service = DualControlTestSupport.service(
                approvals, audit, DualControlTestSupport.config(DualControlTestSupport.TIMEOUT_MS, Map.of()), now);

        // Block-range action: step-up re-authentication is required.
        CommandEvent event = DualControlTestSupport.humanEvent("evt", requester, "DROP TABLE users", now);
        service.raisePending(event, true);

        // A distinct Approver WITHOUT successful step-up is rejected and execution stays withheld.
        ApprovalResult failedStepUp = service.confirm("evt", approver, false);
        assertThat(failedStepUp.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(failedStepUp.reasonCode()).isEqualTo(DualControlService.REASON_STEP_UP_FAILED);
        PendingApproval afterFailedStepUp = service.find("evt").orElseThrow();
        assertThat(afterFailedStepUp.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(afterFailedStepUp.approverId()).isNull();

        // Only a confirmation with successful step-up (distinct Approver) transitions to CONFIRMED.
        ApprovalResult okStepUp = service.confirm("evt", approver, true);
        assertThat(okStepUp.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(okStepUp.reasonCode()).isEqualTo(DualControlService.REASON_CONFIRMED);
        assertThat(service.find("evt").orElseThrow().approverId()).isEqualTo(approver);
    }
}
