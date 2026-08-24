package com.intentguard.dualcontrol;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.CommandEvent;
import com.intentguard.dualcontrol.DualControlTestSupport.InMemoryPendingApprovalRepository;
import com.intentguard.dualcontrol.DualControlTestSupport.RecordingAuditHistoryRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: intentguard-guardrails, Property 22: Dual-control requests and resolutions are recorded.
 *
 * <p>For any dual-control request and its subsequent resolution (confirmed, self-approval-rejected,
 * step-up-rejected, or timed out), corresponding Audit_History records exist for the request and its
 * outcome (Validates: Requirements 4.9).
 */
class DualControlAuditRecordProperties {

    /** The four resolution outcomes a raised approval can reach. */
    enum Resolution {
        CONFIRMED,
        SELF_APPROVAL_REJECTED,
        STEP_UP_REJECTED,
        TIMED_OUT
    }

    @Property(tries = 200)
    void everyRequestAndItsResolutionAreAudited(@ForAll("resolutions") Resolution resolution) {
        InMemoryPendingApprovalRepository approvals = new InMemoryPendingApprovalRepository();
        RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
        long now = 1_000_000L;
        DualControlService service = DualControlTestSupport.service(
                approvals, audit, DualControlTestSupport.config(DualControlTestSupport.TIMEOUT_MS, Map.of()), now);

        boolean stepUpRequired = resolution == Resolution.STEP_UP_REJECTED;
        CommandEvent event = DualControlTestSupport.humanEvent("evt", "alice", "rm -rf /data", now);
        service.raisePending(event, stepUpRequired);

        // The raise is always audited as a DUAL_CONTROL_REQUEST with the PENDING reason (Req 4.9).
        assertThat(audit.recordTypes()).contains(DualControlService.RECORD_TYPE_REQUEST);
        assertThat(audit.reasonCodes()).contains(DualControlService.REASON_PENDING);

        String expectedResolutionReason = drive(service, resolution, now);

        // The resolution is always audited as a DUAL_CONTROL_RESOLVED with the matching reason.
        assertThat(audit.recordTypes()).contains(DualControlService.RECORD_TYPE_RESOLVED);
        assertThat(audit.reasonCodes()).contains(expectedResolutionReason);
    }

    /** Drives the approval to the given resolution and returns the expected audit reason code. */
    private static String drive(DualControlService service, Resolution resolution, long now) {
        return switch (resolution) {
            case CONFIRMED -> {
                service.confirm("evt", "bob", true);
                yield DualControlService.REASON_CONFIRMED;
            }
            case SELF_APPROVAL_REJECTED -> {
                service.confirm("evt", "alice", true);
                yield DualControlService.REASON_SELF_APPROVAL;
            }
            case STEP_UP_REJECTED -> {
                service.confirm("evt", "bob", false);
                yield DualControlService.REASON_STEP_UP_FAILED;
            }
            case TIMED_OUT -> {
                service.expireOverdue(now + DualControlTestSupport.TIMEOUT_MS + 1);
                yield DualControlService.REASON_TIMEOUT;
            }
        };
    }

    @Provide
    Arbitrary<Resolution> resolutions() {
        return Arbitraries.of(Resolution.class);
    }
}
