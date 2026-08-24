package com.intentguard.dualcontrol;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.decision.DefaultDecisionEngine;
import com.intentguard.decision.DualControlStatus;
import com.intentguard.decision.GuardrailContext;
import com.intentguard.decision.GuardrailDecisionEngine;
import com.intentguard.decision.TamperClassifier;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;
import com.intentguard.domain.ProfileState;
import com.intentguard.dualcontrol.DualControlTestSupport.InMemoryPendingApprovalRepository;
import com.intentguard.dualcontrol.DualControlTestSupport.RecordingAuditHistoryRepository;
import com.intentguard.policy.PolicyDecision;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-guardrails, Property 19: An unconfirmed dual-control request times out to a
 * block.
 *
 * <p>For any pending dual-control approval whose current time exceeds its confirmation-timeout
 * deadline, expiry marks it TIMED_OUT, the Corrective_Action becomes BLOCK, and the timeout outcome
 * is recorded in the Audit_History (Validates: Requirements 4.5).
 */
class DualControlTimeoutProperties {

    private final GuardrailDecisionEngine engine = new GuardrailDecisionEngine(
            new DefaultDecisionEngine(new TamperClassifier()), new TamperClassifier());

    @Property(tries = 200)
    void overduePendingTimesOutToBlockAndIsRecorded(
            @ForAll @LongRange(min = 1L, max = 3_600_000L) long timeoutMs,
            @ForAll @LongRange(min = 1L, max = 3_600_000L) long overshootMs) {

        InMemoryPendingApprovalRepository approvals = new InMemoryPendingApprovalRepository();
        RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();
        long now = 1_000_000L;
        DualControlService service = DualControlTestSupport.service(
                approvals, audit, DualControlTestSupport.config(timeoutMs, Map.of()), now);

        CommandEvent event = DualControlTestSupport.humanEvent("evt", "alice", "rm -rf /", now);
        PendingApproval raised = service.raisePending(event, true);
        assertThat(raised.expiresAt()).isEqualTo(now + timeoutMs);

        // Strictly past the deadline: the sweep marks it TIMED_OUT and records the timeout.
        long past = raised.expiresAt() + overshootMs;
        List<PendingApproval> timedOut = service.expireOverdue(past);

        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.get(0).status()).isEqualTo(ApprovalStatus.TIMED_OUT);
        assertThat(service.find("evt").orElseThrow().status()).isEqualTo(ApprovalStatus.TIMED_OUT);
        assertThat(audit.reasonCodes()).contains(DualControlService.REASON_TIMEOUT);

        // The Corrective_Action for a TIMED_OUT approval is BLOCK, regardless of the base score.
        GuardrailContext gc = new GuardrailContext(
                PolicyDecision.none(), BlastRadiusResult.none(), true, DualControlStatus.TIMED_OUT);
        Decision decision = engine.decide(
                benignEvent(now), result(0.0), config(0.4, 0.7), ProfileState.ACTIVE, true, gc);
        assertThat(decision.action()).isEqualTo(CorrectiveAction.BLOCK);
    }

    private static CommandEvent benignEvent(long now) {
        return DualControlTestSupport.humanEvent("evt-benign", "alice", "ls -la", now);
    }

    private static DivergenceResult result(double composite) {
        return new DivergenceResult(composite, List.of(), Set.of());
    }

    private static ThresholdConfiguration config(double ask, double block) {
        return new ThresholdConfiguration(
                1,
                ask,
                block,
                Map.of(
                        ComponentId.SEQUENCE_SURPRISE, 0.25,
                        ComponentId.CONTEXT_MISMATCH, 0.20,
                        ComponentId.BEHAVIORAL_DEVIATION, 0.25,
                        ComponentId.SEMANTIC_INCONSISTENCY, 0.30),
                0.15,
                200,
                5_000L,
                15_000L,
                1_200L,
                1_000L,
                "admin",
                1_000L);
    }
}
