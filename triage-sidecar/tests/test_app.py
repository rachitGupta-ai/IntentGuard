"""End-to-end wiring tests for the assembled Sidecar app (task 20.3).

Exercise the full Integration_Contract flow through :class:`sidecar.app.Sidecar`:

  * happy path: inbound alert -> 201 (new) -> investigation runs -> report
    emitted (stamped + Service_Account authed) -> marked completed;
  * a duplicate within retention returns the identical stored report (200);
  * an in-progress duplicate returns 202;
  * a Control_Tower decision drives dual-control and, on two distinct approvals,
    resumes the paused investigation from its checkpoint; a rejection does not.

All engine / LLM I/O is replaced with deterministic in-memory doubles so the
wiring is verified without external dependencies.
"""

from __future__ import annotations

from typing import Optional

from sidecar.app import Sidecar
from sidecar.config import SidecarConfig
from sidecar.contract import InboundStatus
from sidecar.contract.auth import default_service_account
from sidecar.models import ThreatCategory, TriggerType, VerdictValue
from sidecar.tools import (
    EngineRecord,
    InlineTimeoutRunner,
    ReadToolRegistry,
    StaticEngineBackend,
)
from sidecar.triage import IdempotencyStore
from sidecar.triage.nodes import (
    ProbeCall,
    ScriptedProbeStrategy,
    StaticHypothesisModel,
    StaticVerdictModel,
)

_ALERT_TS_MS = 1_700_000_000_000
_ALERT_ID = "alert-e2e-1"


# --- helpers --------------------------------------------------------------


def _message(**overrides) -> dict:
    base = dict(
        schemaVersion="v1",
        alertId=_ALERT_ID,
        triggerType=TriggerType.SESSION_HIJACK.value,
        actorId="actor-1",
        sessionId="session-1",
        alertTimestampMs=_ALERT_TS_MS,
        signalPayload={},
    )
    base.update(overrides)
    return base


def _seeded_registry() -> ReadToolRegistry:
    backend = StaticEngineBackend(
        session_history=[EngineRecord("audit-sess-1", "session record")],
        actor_profile=[EngineRecord("audit-actor-1", "actor profile")],
        related_alerts=[EngineRecord("audit-rel-1", "related alert")],
        exfil_correlations=[EngineRecord("audit-exfil-1", "exfil correlation")],
        audit_history=[EngineRecord("audit-hist-1", "audit history")],
    )
    return ReadToolRegistry(backend, runner=InlineTimeoutRunner())


def _confident_hypothesis_model() -> StaticHypothesisModel:
    return StaticHypothesisModel(
        [
            {
                "id": "h1",
                "statement": "the actor session was hijacked",
                "threatCategory": ThreatCategory.SESSION_HIJACK.value,
                "supportingEvidence": [
                    {
                        "kind": "context",
                        "summary": "anomalous session fingerprint",
                        "auditRecordId": "audit-sess-1",
                    }
                ],
            }
        ]
    )


def _confident_verdict_model() -> StaticVerdictModel:
    return StaticVerdictModel(
        {
            "value": VerdictValue.CONFIRMED_THREAT.value,
            "confidence": 0.9,
            "threatCategory": ThreatCategory.SESSION_HIJACK.value,
        }
    )


def _fixed_clock(value_ms: int = _ALERT_TS_MS):
    return lambda: value_ms


def _confident_sidecar(
    *,
    idempotency_store: Optional[IdempotencyStore] = None,
) -> Sidecar:
    """A Sidecar wired with doubles that produce a confident verdict."""
    config = SidecarConfig()
    return Sidecar(
        config=config,
        idempotency_store=idempotency_store,
        registry=_seeded_registry(),
        hypothesis_model=_confident_hypothesis_model(),
        verdict_model=_confident_verdict_model(),
        strategy=ScriptedProbeStrategy(
            [
                ProbeCall(
                    tool_name="get_session_history",
                    args={"alert_id": _ALERT_ID},
                    resolve_hypotheses=("h1",),
                )
            ]
        ),
        clock=_fixed_clock(),
        runner=InlineTimeoutRunner(),
    )


# --- happy path: inbound -> 201 -> investigate -> emit -> complete --------


def test_happy_path_admits_new_investigates_and_emits_stamped_authed_report():
    sidecar = _confident_sidecar()

    response = sidecar.handle_alert(_message())

    # New run admitted -> 201 accepted (R2, R3.5).
    assert response.status is InboundStatus.CREATED
    assert response.body == {"alertId": _ALERT_ID, "status": "accepted"}

    # The investigation ran and produced a confident report...
    emitter = sidecar.report_emitter
    sent = emitter.transport.sent  # type: ignore[attr-defined]
    assert len(sent) == 1
    report, headers = sent[0]
    assert report.alertId == _ALERT_ID
    assert report.triggerType is TriggerType.SESSION_HIJACK
    assert report.verdict is VerdictValue.CONFIRMED_THREAT
    assert report.confidence == 0.9
    assert report.recommendedAction is not None
    assert report.recommendedAction.executed is False

    # ...stamped with the schema version (R14.1) and authed with the read-only
    # Service_Account (R14.2).
    assert report.schemaVersion == "v1"
    assert headers["X-Triage-Schema-Version"] == "v1"
    expected_auth = default_service_account().authorization_value
    assert headers["Authorization"] == expected_auth

    # The run was marked completed in the idempotency store (R3.2/R3.6).
    record = sidecar.idempotency.lookup(_ALERT_ID)
    assert record is not None
    assert record.report is not None
    assert record.report.verdict is VerdictValue.CONFIRMED_THREAT


# --- duplicate within retention returns the identical stored report (200) --


def test_duplicate_within_retention_returns_stored_report_200():
    store = IdempotencyStore(clock=lambda: _ALERT_TS_MS / 1000.0)
    sidecar = _confident_sidecar(idempotency_store=store)

    first = sidecar.handle_alert(_message())
    assert first.status is InboundStatus.CREATED

    second = sidecar.handle_alert(_message())

    # Completed within retention -> 200 with the stored report (R3.2, R3.6).
    assert second.status is InboundStatus.OK
    assert second.report is not None
    stored = store.lookup(_ALERT_ID).report
    # The returned report is identical (verdict, confidence, threat, evidence).
    assert second.report.verdict is stored.verdict
    assert second.report.confidence == stored.confidence
    assert second.report.threatCategory is stored.threatCategory
    assert second.report.evidence == stored.evidence

    # No second investigation ran: exactly one report was emitted.
    assert len(sidecar.report_emitter.transport.sent) == 1  # type: ignore[attr-defined]


# --- in-progress duplicate returns 202 ------------------------------------


def test_in_progress_duplicate_returns_202():
    store = IdempotencyStore(clock=lambda: _ALERT_TS_MS / 1000.0)
    sidecar = _confident_sidecar(idempotency_store=store)

    # Pre-seed an active run for the alertId (as if one is already in flight).
    store.admit(_ALERT_ID)

    response = sidecar.handle_alert(_message())

    # Active run exists -> 202 in_progress (R3.3), and no new investigation ran.
    assert response.status is InboundStatus.ACCEPTED
    assert response.body == {"alertId": _ALERT_ID, "status": "in_progress"}
    assert sidecar.report_emitter.transport.sent == []  # type: ignore[attr-defined]


# --- unsupported schema version rejected (409) ----------------------------


def test_unsupported_schema_version_rejected_409():
    sidecar = _confident_sidecar()
    response = sidecar.handle_alert(_message(schemaVersion="v999"))
    assert response.status is InboundStatus.CONFLICT
    assert response.body["error"] == "unsupported_version"
    assert response.body["declaredVersion"] == "v999"


# --- decision channel drives dual-control + resume ------------------------


def _uncertain_sidecar() -> Sidecar:
    """A Sidecar with the fail-open default verdict model (escalates every alert)."""
    return Sidecar(clock=_fixed_clock())


def test_uncertain_alert_escalates_and_pauses_then_dual_control_resumes():
    sidecar = _uncertain_sidecar()

    response = sidecar.handle_alert(_message())

    # A fail-open (uncertain) verdict escalates: 201 admitted, uncertain report.
    assert response.status is InboundStatus.CREATED
    report, _ = sidecar.report_emitter.transport.sent[0]  # type: ignore[attr-defined]
    assert report.verdict is VerdictValue.UNCERTAIN
    assert report.recommendedAction is None

    # The escalate hook persisted + paused the investigation (R11.1).
    assert sidecar.hitl.is_paused(_ALERT_ID) is True

    # First approval: dual-control still pending, not resumed (R11.4).
    first = sidecar.handle_decision(_ALERT_ID, {"approverId": "approver-1", "decision": "approve"})
    assert first.mayProceed is False
    assert first.resumed is False
    assert sidecar.hitl.is_paused(_ALERT_ID) is True

    # Second distinct approval: dual-control approves -> resume (R11.3, R11.4).
    second = sidecar.handle_decision(_ALERT_ID, {"approverId": "approver-2", "decision": "approve"})
    assert second.mayProceed is True
    assert second.resumed is True
    assert second.resumedState is not None
    assert second.resumedState.alertId == _ALERT_ID
    assert sidecar.hitl.is_paused(_ALERT_ID) is False


def test_rejection_is_recorded_and_does_not_resume():
    sidecar = _uncertain_sidecar()
    sidecar.handle_alert(_message())
    assert sidecar.hitl.is_paused(_ALERT_ID) is True

    result = sidecar.handle_decision(_ALERT_ID, {"approverId": "approver-1", "decision": "reject"})

    # A rejection is recorded, blocks the action, and never resumes (R11.5).
    assert result.mayProceed is False
    assert result.resumed is False
    assert sidecar.hitl.is_paused(_ALERT_ID) is True


def test_same_approver_twice_does_not_proceed():
    sidecar = _uncertain_sidecar()
    sidecar.handle_alert(_message())

    sidecar.handle_decision(_ALERT_ID, {"approverId": "approver-1", "decision": "approve"})
    # Same identity again must not count toward the two required approvers (R11.4).
    dup = sidecar.handle_decision(_ALERT_ID, {"approverId": "approver-1", "decision": "approve"})

    assert dup.mayProceed is False
    assert dup.resumed is False
    assert sidecar.hitl.is_paused(_ALERT_ID) is True
