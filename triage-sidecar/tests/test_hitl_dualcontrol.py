"""Unit tests for dual-control approval/rejection (task 19.3, R11.4, R11.5).

Example / edge-case coverage for the four-eyes gate:
  * two DISTINCT approvers -> APPROVED and the action may proceed (R11.4)
  * a same-identity second approval is refused and the gate stays PENDING (R11.4)
  * a rejection is recorded, blocks the action, and dominates approvals (R11.5)
  * a rejection after one approval blocks the action (R11.5)

Property 18 (universal) is covered separately by the property-based test (19.4).
"""

from __future__ import annotations

from sidecar.contract.outbound import ControlTowerDecision, DecisionMessage
from sidecar.hitl import (
    DualControlGate,
    DualControlStatus,
    HITLManager,
    InMemoryCheckpointStore,
    REQUIRED_APPROVERS,
)
from sidecar.models import AlertEnvelope, TriageState, TriggerType


def _decision(
    approver: str,
    decision: ControlTowerDecision = ControlTowerDecision.APPROVE,
    alert_id: str = "alert-1",
) -> DecisionMessage:
    return DecisionMessage(
        alertId=alert_id,
        approverId=approver,
        decision=decision,
        stepUpAuthenticated=True,
    )


def _state(alert_id: str = "alert-1") -> TriageState:
    envelope = AlertEnvelope(
        schemaVersion="v1",
        alertId=alert_id,
        triggerType=TriggerType.CANARY_TOKEN,
        alertTimestampMs=1_000,
        signalPayload={"canary": "hit"},
    )
    return TriageState(
        alertId=alert_id,
        envelope=envelope,
        triggerType=TriggerType.CANARY_TOKEN,
        investigation_started_ms=1_000,
        escalated=True,
    )


# --- two distinct approvers proceed (R11.4) ---------------------------------


def test_two_distinct_approvers_may_proceed() -> None:
    gate = DualControlGate()

    first = gate.submit(_decision("approver-a"))
    assert first.accepted is True
    assert first.status is DualControlStatus.PENDING
    assert first.mayProceed is False

    second = gate.submit(_decision("approver-b"))
    assert second.accepted is True
    assert second.status is DualControlStatus.APPROVED
    assert second.mayProceed is True
    assert second.approvers == ("approver-a", "approver-b")

    assert gate.may_proceed("alert-1") is True
    assert gate.approvers("alert-1") == ("approver-a", "approver-b")


def test_required_approvers_is_two() -> None:
    assert REQUIRED_APPROVERS == 2


# --- same-identity second approval refused (R11.4) --------------------------


def test_same_identity_second_approval_is_refused_and_stays_pending() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a"))
    duplicate = gate.submit(_decision("approver-a"))

    assert duplicate.accepted is False
    assert duplicate.status is DualControlStatus.PENDING
    assert duplicate.mayProceed is False
    assert duplicate.approvers == ("approver-a",)
    assert gate.may_proceed("alert-1") is False


def test_distinct_approver_after_refused_duplicate_proceeds() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a"))
    gate.submit(_decision("approver-a"))  # refused duplicate
    outcome = gate.submit(_decision("approver-b"))

    assert outcome.accepted is True
    assert outcome.status is DualControlStatus.APPROVED
    assert outcome.mayProceed is True


# --- rejection blocks (R11.5) -----------------------------------------------


def test_rejection_is_recorded_and_blocks_action() -> None:
    gate = DualControlGate()

    outcome = gate.submit(_decision("approver-a", ControlTowerDecision.REJECT))

    assert outcome.accepted is True
    assert outcome.status is DualControlStatus.REJECTED
    assert outcome.mayProceed is False
    assert gate.is_rejected("alert-1") is True
    assert gate.may_proceed("alert-1") is False


def test_rejection_after_one_approval_blocks_action() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a"))
    outcome = gate.submit(_decision("approver-b", ControlTowerDecision.REJECT))

    assert outcome.status is DualControlStatus.REJECTED
    assert outcome.mayProceed is False
    assert gate.may_proceed("alert-1") is False


def test_approval_after_rejection_is_refused_and_stays_rejected() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a", ControlTowerDecision.REJECT))
    outcome = gate.submit(_decision("approver-b"))

    assert outcome.accepted is False
    assert outcome.status is DualControlStatus.REJECTED
    assert outcome.mayProceed is False


def test_rejection_dominates_even_after_two_approvals() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a"))
    gate.submit(_decision("approver-b"))
    assert gate.may_proceed("alert-1") is True

    outcome = gate.submit(_decision("approver-c", ControlTowerDecision.REJECT))
    assert outcome.status is DualControlStatus.REJECTED
    assert outcome.mayProceed is False
    assert gate.may_proceed("alert-1") is False


# --- unknown alert defaults -------------------------------------------------


def test_unknown_alert_defaults_to_pending() -> None:
    gate = DualControlGate()
    assert gate.status("nope") is DualControlStatus.PENDING
    assert gate.may_proceed("nope") is False
    assert gate.approvers("nope") == ()
    assert gate.is_rejected("nope") is False


# --- per-alert isolation ----------------------------------------------------


def test_gate_tracks_alerts_independently() -> None:
    gate = DualControlGate()

    gate.submit(_decision("approver-a", alert_id="alert-1"))
    gate.submit(_decision("approver-b", alert_id="alert-1"))
    gate.submit(_decision("approver-a", alert_id="alert-2"))

    assert gate.may_proceed("alert-1") is True
    assert gate.may_proceed("alert-2") is False


# --- HITLManager integration ------------------------------------------------


def test_manager_submit_decision_records_and_evaluates() -> None:
    manager = HITLManager(InMemoryCheckpointStore())
    manager.escalate_and_pause(_state())

    first = manager.submit_decision(_decision("approver-a"))
    assert first.mayProceed is False
    assert manager.may_proceed("alert-1") is False

    second = manager.submit_decision(_decision("approver-b"))
    assert second.mayProceed is True
    assert manager.may_proceed("alert-1") is True
    assert manager.dual_control_status("alert-1") is DualControlStatus.APPROVED

    # Both decisions were retained for the paused investigation (R11.2).
    assert len(manager.recorded_decisions("alert-1")) == 2


def test_manager_rejection_blocks_and_is_reflected() -> None:
    manager = HITLManager(InMemoryCheckpointStore())
    manager.escalate_and_pause(_state())

    manager.submit_decision(_decision("approver-a"))
    outcome = manager.submit_decision(_decision("approver-b", ControlTowerDecision.REJECT))

    assert outcome.status is DualControlStatus.REJECTED
    assert manager.may_proceed("alert-1") is False
    assert manager.dual_control_status("alert-1") is DualControlStatus.REJECTED
