package com.intentguard.api;

import java.time.Clock;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intentguard.config.InvalidThresholdConfigException;
import com.intentguard.config.ThresholdConfigUpdate;
import com.intentguard.config.ThresholdConfiguration;
import com.intentguard.config.ThresholdConfigurationService;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.dualcontrol.ApprovalResult;
import com.intentguard.dualcontrol.ApprovalStatus;
import com.intentguard.dualcontrol.DualControlService;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.AuditHistoryRepository;

/**
 * REST surface of the Control_Tower API (design {@code ControlTowerApi}).
 *
 * <p>Exposes three Administrator operations:
 * <ul>
 *   <li>{@code GET /api/history} — Audit_History query by user and time range (Req 11.3);</li>
 *   <li>{@code PUT /api/thresholds} — hot-reload of the Threshold_Configuration, applied to
 *       subsequent Command_Events without a restart; an invalid update is rejected with HTTP 400
 *       and the previously active configuration is retained (Req 7.5);</li>
 *   <li>{@code POST /api/events/{eventId}/resolve} — records the Administrator's resolution of a
 *       pending {@code ask} Command_Event (confirm/proceed vs. block) to the Audit_History
 *       (Req 12.5).</li>
 *   <li>{@code POST /api/events/{eventId}/approve} — a distinct Approver confirms a pending
 *       DualControl (four-eyes) approval; a successful confirmation returns 200 and permits the
 *       Command_Event, while a self-approval, failed step-up, or unknown event returns a 4xx and
 *       the event remains withheld (Req 4.1, 4.4, 4.6, 4.7).</li>
 * </ul>
 *
 * <p><strong>SECURITY — UNAUTHENTICATED ADMIN ENDPOINTS.</strong> These endpoints perform
 * privileged Administrator actions (reading full audit history, mutating enforcement thresholds,
 * and overriding pending decisions) but currently have <em>no authentication or authorization</em>
 * layer. This is acceptable only for the hackathon prototype. Per the reference-monitor trust
 * model, before any non-prototype use these endpoints MUST be protected — e.g. bound to a
 * loopback/OS-restricted interface owned by the {@code intentguard} service account, and/or placed
 * behind an authenticating filter (mTLS, signed admin token, or a reverse proxy enforcing operator
 * identity). Do not expose this controller on an untrusted network as-is.
 */
@RestController
@RequestMapping("/api")
public class ControlTowerController {

    private static final String DEFAULT_ADMIN = "admin";

    private final AuditHistoryRepository auditHistoryRepository;
    private final ThresholdConfigurationService thresholdConfigurationService;
    private final DualControlService dualControlService;

    // Not a Spring bean by default; injected via setter in tests for deterministic timestamps.
    private Clock clock = Clock.systemUTC();

    public ControlTowerController(
            AuditHistoryRepository auditHistoryRepository,
            ThresholdConfigurationService thresholdConfigurationService,
            DualControlService dualControlService) {
        this.auditHistoryRepository = auditHistoryRepository;
        this.thresholdConfigurationService = thresholdConfigurationService;
        this.dualControlService = dualControlService;
    }

    /** Test seam for deterministic resolution timestamps. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the Audit_History records for {@code userId} whose timestamp falls within
     * {@code [from, to]} inclusive, oldest-first (Req 11.3). The returned records are exactly those
     * matching the user and time-range constraints.
     */
    @GetMapping("/history")
    public List<AuditHistoryDocument> queryHistory(
            @RequestParam String userId,
            @RequestParam long from,
            @RequestParam long to) {
        return auditHistoryRepository.queryByUserAndTimeRange(userId, from, to);
    }

    /**
     * Validates and applies an Administrator threshold update, returning the new active
     * configuration (Req 7.5). Invalid updates are rejected by
     * {@link #onInvalidThresholdConfig(InvalidThresholdConfigException)} with HTTP 400, and the
     * previously active configuration remains in effect.
     */
    @PutMapping("/thresholds")
    public ThresholdConfiguration updateThresholds(@RequestBody ThresholdConfigUpdate update) {
        return thresholdConfigurationService.applyUpdate(update, DEFAULT_ADMIN);
    }

    /**
     * Records the Administrator's resolution of a pending {@code ask} Command_Event to the
     * Audit_History (Req 12.5). {@code ALLOW} confirms the command (it may proceed); {@code BLOCK}
     * refuses it. The resolution is persisted as an {@code ASK_RESOLUTION} audit record capturing
     * the event id and the chosen Corrective_Action.
     */
    @PostMapping("/events/{eventId}/resolve")
    public AskResolutionResponse resolveAsk(
            @PathVariable String eventId, @RequestBody ResolveAskRequest request) {
        CorrectiveAction action = request.action();
        if (action == null) {
            throw new IllegalArgumentException("resolution action must be provided");
        }
        String resolvedBy = (request.resolvedBy() == null || request.resolvedBy().isBlank())
                ? DEFAULT_ADMIN
                : request.resolvedBy();
        long now = clock.millis();

        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(eventId);
        record.setCorrectiveAction(action.name());
        record.setReasonCode("ADMIN_ASK_RESOLUTION");
        record.setRecordType("ASK_RESOLUTION");
        record.setHumanPrincipalId(resolvedBy);
        record.setTimestamp(now);
        auditHistoryRepository.save(record);

        return new AskResolutionResponse(eventId, action.name(), resolvedBy, "ASK_RESOLUTION", now);
    }

    /**
     * Confirms a pending DualControl (four-eyes) approval on behalf of a distinct Approver
     * (Req 4.1, 4.4, 4.6, 4.7). Delegates to {@link DualControlService#confirm(String, String,
     * boolean)}:
     * <ul>
     *   <li>a successful {@link ApprovalStatus#CONFIRMED} (distinct Approver, step-up satisfied
     *       where required) returns HTTP 200 and permits the Command_Event (Req 4.4);</li>
     *   <li>a self-approval or failed/absent step-up is rejected with HTTP 409 Conflict and the
     *       event remains withheld pending a valid distinct Approver (Req 4.3, 4.7);</li>
     *   <li>an unknown event id returns HTTP 404 Not Found.</li>
     * </ul>
     * The {@code reasonCode} from the service is echoed in the {@link ApprovalResponse} for both
     * outcomes.
     */
    @PostMapping("/events/{eventId}/approve")
    public ResponseEntity<ApprovalResponse> approve(
            @PathVariable String eventId, @RequestBody ApproveRequest request) {
        if (request.approverId() == null || request.approverId().isBlank()) {
            throw new IllegalArgumentException("approverId must be provided");
        }
        ApprovalResult result =
                dualControlService.confirm(eventId, request.approverId(), request.stepUpAuthenticated());
        ApprovalResponse body = new ApprovalResponse(
                eventId, result.status().name(), result.reasonCode(), request.approverId());

        if (result.status() == ApprovalStatus.CONFIRMED) {
            return ResponseEntity.ok(body);
        }
        HttpStatus status =
                DualControlService.REASON_UNKNOWN_EVENT.equals(result.reasonCode())
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps a rejected threshold update to HTTP 400, echoing the validation message and the
     * previously active configuration that remains in effect (Req 7.5).
     */
    @ExceptionHandler(InvalidThresholdConfigException.class)
    public ResponseEntity<ThresholdUpdateErrorResponse> onInvalidThresholdConfig(
            InvalidThresholdConfigException ex) {
        ThresholdConfiguration previous =
                thresholdConfigurationService.getActiveConfig().orElse(null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ThresholdUpdateErrorResponse(ex.getMessage(), previous));
    }
}
