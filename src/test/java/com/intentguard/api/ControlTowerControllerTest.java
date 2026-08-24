package com.intentguard.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.intentguard.config.InvalidThresholdConfigException;
import com.intentguard.config.ThresholdConfigUpdate;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.dualcontrol.ApprovalResult;
import com.intentguard.dualcontrol.ApprovalStatus;
import com.intentguard.dualcontrol.DualControlService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

/**
 * Unit tests for {@link ControlTowerController} using mocked collaborators (Req 7.5, 11.3, 12.5).
 * The controller methods are exercised directly with mocked {@link AuditHistoryRepository} and
 * {@link ThresholdConfigurationService} so the tests stay fast and require no Spring context or
 * MongoDB.
 */
class ControlTowerControllerTest {

    private AuditHistoryRepository auditHistoryRepository;
    private ThresholdConfigurationService thresholdConfigurationService;
    private DualControlService dualControlService;
    private ControlTowerController controller;

    @BeforeEach
    void setUp() {
        auditHistoryRepository = org.mockito.Mockito.mock(AuditHistoryRepository.class);
        thresholdConfigurationService = org.mockito.Mockito.mock(ThresholdConfigurationService.class);
        dualControlService = org.mockito.Mockito.mock(DualControlService.class);
        controller = new ControlTowerController(
                auditHistoryRepository, thresholdConfigurationService, dualControlService);
        controller.setClock(Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
    }

    @Test
    void queryHistoryReturnsMatchingRecords() {
        AuditHistoryDocument record = auditRecord("evt-1", "alice", 1_710_000_000_500L);
        when(auditHistoryRepository.queryByUserAndTimeRange("alice", 1_710_000_000_000L, 1_710_000_001_000L))
                .thenReturn(List.of(record));

        List<AuditHistoryDocument> result =
                controller.queryHistory("alice", 1_710_000_000_000L, 1_710_000_001_000L);

        assertThat(result).containsExactly(record);
        verify(auditHistoryRepository)
                .queryByUserAndTimeRange("alice", 1_710_000_000_000L, 1_710_000_001_000L);
    }

    @Test
    void updateThresholdsValidAppliesAndReturnsNewConfig() {
        ThresholdConfigUpdate update = validUpdate(0.4, 0.7);
        ThresholdConfiguration applied = configFromUpdate(2, update);
        when(thresholdConfigurationService.applyUpdate(eq(update), eq("admin"))).thenReturn(applied);

        ThresholdConfiguration result = controller.updateThresholds(update);

        assertThat(result).isEqualTo(applied);
        verify(thresholdConfigurationService).applyUpdate(update, "admin");
    }

    @Test
    void updateThresholdsInvalidReturns400AndRetainsPreviousConfig() {
        ThresholdConfigUpdate invalid = validUpdate(0.9, 0.2); // ask > block, invalid
        ThresholdConfiguration previous = configFromUpdate(1, validUpdate(0.4, 0.7));
        when(thresholdConfigurationService.applyUpdate(eq(invalid), eq("admin")))
                .thenThrow(new InvalidThresholdConfigException("askThreshold must be <= blockThreshold"));
        when(thresholdConfigurationService.getActiveConfig()).thenReturn(Optional.of(previous));

        InvalidThresholdConfigException thrown =
                assertThrows(InvalidThresholdConfigException.class, () -> controller.updateThresholds(invalid));

        ResponseEntity<ThresholdUpdateErrorResponse> response =
                controller.onInvalidThresholdConfig(thrown);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("askThreshold");
        // The previously active configuration is retained and reported unchanged.
        assertThat(response.getBody().previousConfig()).isEqualTo(previous);
    }

    @Test
    void resolveAskRecordsResolution() {
        ResolveAskRequest request = new ResolveAskRequest(CorrectiveAction.BLOCK, "operator-1");

        AskResolutionResponse response = controller.resolveAsk("evt-42", request);

        ArgumentCaptor<AuditHistoryDocument> captor =
                ArgumentCaptor.forClass(AuditHistoryDocument.class);
        verify(auditHistoryRepository).save(captor.capture());
        AuditHistoryDocument saved = captor.getValue();

        assertThat(saved.getEventId()).isEqualTo("evt-42");
        assertThat(saved.getCorrectiveAction()).isEqualTo("BLOCK");
        assertThat(saved.getRecordType()).isEqualTo("ASK_RESOLUTION");
        assertThat(saved.getReasonCode()).isEqualTo("ADMIN_ASK_RESOLUTION");
        assertThat(saved.getHumanPrincipalId()).isEqualTo("operator-1");
        assertThat(saved.getTimestamp()).isEqualTo(1_710_000_000_000L);

        assertThat(response.eventId()).isEqualTo("evt-42");
        assertThat(response.action()).isEqualTo("BLOCK");
        assertThat(response.resolvedBy()).isEqualTo("operator-1");
        assertThat(response.recordType()).isEqualTo("ASK_RESOLUTION");
        assertThat(response.timestamp()).isEqualTo(1_710_000_000_000L);
    }

    @Test
    void resolveAskDefaultsResolverToAdminWhenMissing() {
        ResolveAskRequest request = new ResolveAskRequest(CorrectiveAction.ALLOW, null);

        AskResolutionResponse response = controller.resolveAsk("evt-7", request);

        assertThat(response.resolvedBy()).isEqualTo("admin");
        assertThat(response.action()).isEqualTo("ALLOW");
    }

    // --- POST /api/events/{eventId}/approve (Req 4.3, 4.6, 4.7) ---

    @Test
    void approveHappyPathConfirmsAndReturns200() {
        // Distinct approver with successful step-up on a step-up-required pending approval -> CONFIRMED.
        when(dualControlService.confirm("evt-1", "approver-bob", true))
                .thenReturn(new ApprovalResult(ApprovalStatus.CONFIRMED, DualControlService.REASON_CONFIRMED));

        ResponseEntity<ApprovalResponse> response =
                controller.approve("evt-1", new ApproveRequest("approver-bob", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().eventId()).isEqualTo("evt-1");
        assertThat(response.getBody().status()).isEqualTo("CONFIRMED");
        assertThat(response.getBody().reasonCode()).isEqualTo(DualControlService.REASON_CONFIRMED);
        assertThat(response.getBody().approverId()).isEqualTo("approver-bob");
        verify(dualControlService).confirm("evt-1", "approver-bob", true);
    }

    @Test
    void approveSelfApprovalRejectedWith409AndStaysWithheld() {
        when(dualControlService.confirm("evt-2", "alice", true))
                .thenReturn(new ApprovalResult(ApprovalStatus.REJECTED, DualControlService.REASON_SELF_APPROVAL));

        ResponseEntity<ApprovalResponse> response =
                controller.approve("evt-2", new ApproveRequest("alice", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        assertThat(response.getBody().reasonCode()).isEqualTo(DualControlService.REASON_SELF_APPROVAL);
    }

    @Test
    void approveFailedStepUpRejectedWith409AndStaysWithheld() {
        when(dualControlService.confirm("evt-3", "approver-bob", false))
                .thenReturn(new ApprovalResult(ApprovalStatus.REJECTED, DualControlService.REASON_STEP_UP_FAILED));

        ResponseEntity<ApprovalResponse> response =
                controller.approve("evt-3", new ApproveRequest("approver-bob", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        assertThat(response.getBody().reasonCode()).isEqualTo(DualControlService.REASON_STEP_UP_FAILED);
    }

    @Test
    void approveUnknownEventReturns404() {
        when(dualControlService.confirm("missing", "approver-bob", true))
                .thenReturn(new ApprovalResult(ApprovalStatus.REJECTED, DualControlService.REASON_UNKNOWN_EVENT));

        ResponseEntity<ApprovalResponse> response =
                controller.approve("missing", new ApproveRequest("approver-bob", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().reasonCode()).isEqualTo(DualControlService.REASON_UNKNOWN_EVENT);
    }

    private static AuditHistoryDocument auditRecord(String eventId, String userId, long timestamp) {
        AuditHistoryDocument doc = new AuditHistoryDocument();
        doc.setEventId(eventId);
        doc.setUserId(userId);
        doc.setTimestamp(timestamp);
        doc.setRecordType("DECISION");
        return doc;
    }

    private static ThresholdConfigUpdate validUpdate(double ask, double block) {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        return new ThresholdConfigUpdate(ask, block, weights, 0.15, 200, 5000, 15000, 1200, 1000);
    }

    private static ThresholdConfiguration configFromUpdate(int version, ThresholdConfigUpdate update) {
        return ThresholdConfiguration.fromUpdate(version, update, "admin", 1_710_000_000_000L);
    }
}
