package com.intentguard.dualcontrol;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.intentguard.blastradius.GuardrailConfig;
import com.intentguard.blastradius.GuardrailConfigService;
import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.GuardrailConfigRepository;
import com.intentguard.persistence.PendingApprovalRepository;
import com.mongodb.client.MongoDatabase;

/**
 * Unit tests for {@link DualControlService} and {@link CapabilityScope} using deterministic
 * in-memory fakes and a fixed {@link Clock} (Req 4.1–4.9). Covers: raise → withhold, self-approval
 * rejection, failed-step-up rejection, distinct-approver confirmation recording the Approver,
 * the timeout sweep to {@code TIMED_OUT}, idempotency, audit-record emission, and the
 * capability-scope in/out check.
 */
class DualControlServiceTest {

    private static final long NOW = 1_710_000_000_000L;
    private static final long TIMEOUT_MS = 300_000L;

    private final InMemoryPendingApprovalRepository approvals = new InMemoryPendingApprovalRepository();
    private final RecordingAuditHistoryRepository audit = new RecordingAuditHistoryRepository();

    private DualControlService serviceWith(GuardrailConfig config, long nowMs) {
        DualControlService service = new DualControlService(
                approvals, audit, new FixedGuardrailConfigService(config));
        service.setClock(Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC));
        return service;
    }

    private DualControlService serviceWithDefaults(long nowMs) {
        return serviceWith(defaultConfig(), nowMs);
    }

    private static GuardrailConfig defaultConfig() {
        return new GuardrailConfig(
                1, List.of(), 100, List.of(), 0.90, TIMEOUT_MS,
                Map.of("agent-ci-bot", List.of("build", "test", "vcs")),
                Map.of(), "admin", 0L);
    }

    private static CommandEvent event(String eventId, Actor actor, String commandText) {
        return new CommandEvent(
                eventId, actor, "sess-1", commandText, "/repo", "repo",
                Map.of(), NOW, InputOrigin.TYPED, SignalSource.HOOK, IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    // ---- raise → withhold (Req 4.1, 4.2, 4.5, 4.9) --------------------------------------------

    @Test
    void raisePendingPersistsPendingApprovalWithConfiguredExpiryAndAudits() {
        DualControlService service = serviceWithDefaults(NOW);
        CommandEvent e = event("evt-1", Actor.human("alice"), "git push");

        PendingApproval raised = service.raisePending(e, true);

        assertThat(raised.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(raised.requesterId()).isEqualTo("alice");
        assertThat(raised.approverId()).isNull();
        assertThat(raised.stepUpRequired()).isTrue();
        assertThat(raised.raisedAt()).isEqualTo(NOW);
        assertThat(raised.expiresAt()).isEqualTo(NOW + TIMEOUT_MS);
        assertThat(raised.resolvedAt()).isNull();

        // Execution withheld: the approval is stored PENDING and discoverable.
        assertThat(service.find("evt-1")).contains(raised);
        assertThat(approvals.findPending()).extracting(PendingApproval::eventId).containsExactly("evt-1");

        assertThat(audit.saved).hasSize(1);
        AuditHistoryDocument req = audit.saved.get(0);
        assertThat(req.getRecordType()).isEqualTo(DualControlService.RECORD_TYPE_REQUEST);
        assertThat(req.getReasonCode()).isEqualTo(DualControlService.REASON_PENDING);
        assertThat(req.getEventId()).isEqualTo("evt-1");
        assertThat(req.getUserId()).isEqualTo("alice");
    }

    @Test
    void raisePendingFallsBackToDefaultTimeoutWhenNoActiveConfig() {
        DualControlService service = serviceWith(null, NOW);
        CommandEvent e = event("evt-x", Actor.human("alice"), "git push");

        PendingApproval raised = service.raisePending(e, false);

        assertThat(raised.expiresAt())
                .isEqualTo(NOW + GuardrailConfig.DEFAULT_DUAL_CONTROL_CONFIRMATION_TIMEOUT_MS);
    }

    // ---- self-approval rejected (Req 4.3) ------------------------------------------------------

    @Test
    void confirmRejectsSelfApprovalAndKeepsWithholding() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-2", Actor.human("alice"), "rm -rf /data"), false);

        ApprovalResult result = service.confirm("evt-2", "alice", true);

        assertThat(result.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(DualControlService.REASON_SELF_APPROVAL);
        // Still withheld: the approval remains PENDING so a distinct Approver may still confirm.
        assertThat(service.find("evt-2").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(auditReasons()).contains(DualControlService.REASON_SELF_APPROVAL);
    }

    @Test
    void distinctApproverCanConfirmAfterSelfApprovalRejection() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-2b", Actor.human("alice"), "rm -rf /data"), false);
        service.confirm("evt-2b", "alice", true); // self-approval rejected, stays PENDING

        ApprovalResult result = service.confirm("evt-2b", "bob", true);

        assertThat(result.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(service.find("evt-2b").orElseThrow().approverId()).isEqualTo("bob");
    }

    // ---- step-up rejected (Req 4.7) ------------------------------------------------------------

    @Test
    void confirmRejectsFailedStepUpWhenStepUpRequiredAndKeepsWithholding() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-3", Actor.human("alice"), "DROP TABLE users"), true);

        ApprovalResult result = service.confirm("evt-3", "bob", false);

        assertThat(result.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(DualControlService.REASON_STEP_UP_FAILED);
        assertThat(service.find("evt-3").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(auditReasons()).contains(DualControlService.REASON_STEP_UP_FAILED);
    }

    @Test
    void confirmAllowsMissingStepUpWhenNotRequired() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-3b", Actor.human("alice"), "kubectl scale"), false);

        ApprovalResult result = service.confirm("evt-3b", "bob", false);

        assertThat(result.status()).isEqualTo(ApprovalStatus.CONFIRMED);
    }

    // ---- distinct-approver confirm records the Approver (Req 4.4, 4.9) ------------------------

    @Test
    void confirmByDistinctApproverWithStepUpRecordsApproverAndAudits() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-4", Actor.human("alice"), "DROP TABLE users"), true);

        ApprovalResult result = service.confirm("evt-4", "bob", true);

        assertThat(result.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(result.reasonCode()).isEqualTo(DualControlService.REASON_CONFIRMED);
        PendingApproval confirmed = service.find("evt-4").orElseThrow();
        assertThat(confirmed.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        assertThat(confirmed.approverId()).isEqualTo("bob");
        assertThat(confirmed.resolvedAt()).isEqualTo(NOW);
        assertThat(approvals.findPending()).isEmpty();

        AuditHistoryDocument resolved = lastResolved();
        assertThat(resolved.getRecordType()).isEqualTo(DualControlService.RECORD_TYPE_RESOLVED);
        assertThat(resolved.getReasonCode()).isEqualTo(DualControlService.REASON_CONFIRMED);
        assertThat(resolved.getHumanPrincipalId()).isEqualTo("bob");
    }

    @Test
    void confirmIsIdempotentForAlreadyResolvedEvents() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-5", Actor.human("alice"), "git push"), false);
        service.confirm("evt-5", "bob", true);
        int auditCountAfterFirstConfirm = audit.saved.size();

        ApprovalResult second = service.confirm("evt-5", "carol", true);

        assertThat(second.status()).isEqualTo(ApprovalStatus.CONFIRMED);
        // Idempotent: approver unchanged and no additional state/audit change.
        assertThat(service.find("evt-5").orElseThrow().approverId()).isEqualTo("bob");
        assertThat(audit.saved).hasSize(auditCountAfterFirstConfirm);
    }

    @Test
    void confirmUnknownEventIsRejected() {
        DualControlService service = serviceWithDefaults(NOW);

        ApprovalResult result = service.confirm("missing", "bob", true);

        assertThat(result.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(DualControlService.REASON_UNKNOWN_EVENT);
    }

    // ---- timeout sweep → TIMED_OUT (Req 4.5, 4.9) ---------------------------------------------

    @Test
    void expireOverdueMarksPendingPastExpiryAsTimedOutAndAudits() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-6", Actor.human("alice"), "rm -rf /"), true);
        long past = NOW + TIMEOUT_MS + 1;

        List<PendingApproval> timedOut = service.expireOverdue(past);

        assertThat(timedOut).extracting(PendingApproval::eventId).containsExactly("evt-6");
        assertThat(timedOut.get(0).status()).isEqualTo(ApprovalStatus.TIMED_OUT);
        assertThat(timedOut.get(0).resolvedAt()).isEqualTo(past);
        assertThat(service.find("evt-6").orElseThrow().status()).isEqualTo(ApprovalStatus.TIMED_OUT);
        assertThat(auditReasons()).contains(DualControlService.REASON_TIMEOUT);
    }

    @Test
    void expireOverdueLeavesApprovalsWithinTimeoutPending() {
        DualControlService service = serviceWithDefaults(NOW);
        service.raisePending(event("evt-7", Actor.human("alice"), "rm -rf /"), true);

        // At exactly expiresAt the approval is still within the window (not yet past).
        List<PendingApproval> timedOut = service.expireOverdue(NOW + TIMEOUT_MS);

        assertThat(timedOut).isEmpty();
        assertThat(service.find("evt-7").orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    // ---- capability scope in/out (Req 4.8) -----------------------------------------------------

    @Test
    void capabilityScopeAllowsAgentCommandClassInScope() {
        DualControlService service = serviceWithDefaults(NOW);
        // "git status" normalizes to category "vcs", which is in agent-ci-bot's scope.
        CommandEvent inScope = event("evt-8", Actor.agent("agent-ci-bot", "alice"), "git status");

        assertThat(service.withinCapabilityScope(inScope)).isTrue();
    }

    @Test
    void capabilityScopeRejectsAgentCommandClassOutOfScope() {
        DualControlService service = serviceWithDefaults(NOW);
        // "kubectl delete ns x" normalizes to category "orchestration", NOT in scope.
        CommandEvent outOfScope = event("evt-9", Actor.agent("agent-ci-bot", "alice"), "kubectl delete ns x");

        assertThat(service.withinCapabilityScope(outOfScope)).isFalse();
    }

    @Test
    void capabilityScopeTreatsHumanAndUnconfiguredAgentAsWithinScope() {
        DualControlService service = serviceWithDefaults(NOW);
        CommandEvent human = event("evt-10", Actor.human("alice"), "kubectl delete ns x");
        CommandEvent unconfiguredAgent = event("evt-11", Actor.agent("other-bot", "alice"), "kubectl delete ns x");

        assertThat(service.withinCapabilityScope(human)).isTrue();
        assertThat(service.withinCapabilityScope(unconfiguredAgent)).isTrue();
    }

    @Test
    void capabilityScopeHelperIsUnconstrainedWhenNoScopesConfigured() {
        CommandEvent agent = event("evt-12", Actor.agent("agent-ci-bot", "alice"), "kubectl delete ns x");

        assertThat(CapabilityScope.isWithinScope(agent, null)).isTrue();
        assertThat(CapabilityScope.isWithinScope(agent, Map.of())).isTrue();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private List<String> auditReasons() {
        List<String> reasons = new ArrayList<>();
        for (AuditHistoryDocument doc : audit.saved) {
            reasons.add(doc.getReasonCode());
        }
        return reasons;
    }

    private AuditHistoryDocument lastResolved() {
        AuditHistoryDocument last = null;
        for (AuditHistoryDocument doc : audit.saved) {
            if (DualControlService.RECORD_TYPE_RESOLVED.equals(doc.getRecordType())) {
                last = doc;
            }
        }
        assertThat(last).isNotNull();
        return last;
    }

    // ---- fakes ---------------------------------------------------------------------------------

    private static final class InMemoryPendingApprovalRepository extends PendingApprovalRepository {
        private final Map<String, PendingApproval> byEventId = new HashMap<>();

        InMemoryPendingApprovalRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(PendingApproval approval) {
            byEventId.put(approval.eventId(), approval);
        }

        @Override
        public Optional<PendingApproval> findByEventId(String eventId) {
            return Optional.ofNullable(byEventId.get(eventId));
        }

        @Override
        public List<PendingApproval> findByStatus(ApprovalStatus status) {
            return byEventId.values().stream()
                    .filter(a -> a.status() == status)
                    .sorted((a, b) -> Long.compare(a.raisedAt(), b.raisedAt()))
                    .toList();
        }
    }

    private static final class RecordingAuditHistoryRepository extends AuditHistoryRepository {
        private final List<AuditHistoryDocument> saved = new ArrayList<>();

        RecordingAuditHistoryRepository() {
            super(mock(MongoDatabase.class));
        }

        @Override
        public void save(AuditHistoryDocument record) {
            saved.add(record);
        }
    }

    private static final class FixedGuardrailConfigService extends GuardrailConfigService {
        private final GuardrailConfig config;

        FixedGuardrailConfigService(GuardrailConfig config) {
            super(mock(GuardrailConfigRepository.class));
            this.config = config;
        }

        @Override
        public Optional<GuardrailConfig> getActiveConfig() {
            return Optional.ofNullable(config);
        }
    }
}
