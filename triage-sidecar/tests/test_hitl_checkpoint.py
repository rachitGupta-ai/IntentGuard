"""Unit tests for persist-then-pause and resume-with-integrity (task 19.1, R11).

Example / edge-case coverage for the checkpoint store and HITL manager:
  * durable persist pauses the investigation and retains the checkpoint (R11.1, R11.2)
  * persistence failure neither pauses nor advances and raises
    StateNotPersistedError, retaining pre-escalation state (R11.7)
  * a corrupt / unretrievable checkpoint on a decision does not resume, stays
    paused, and raises CheckpointNotRestoredError (R11.8)
  * a happy-path decision resumes from the persisted checkpoint (R11.3)

Property 19 (universal) is covered separately by the property-based test (19.2).
"""

from __future__ import annotations

import pytest

from sidecar.contract.outbound import ControlTowerDecision, DecisionMessage
from sidecar.hitl import (
    CheckpointNotRestoredError,
    HITLManager,
    InMemoryCheckpointStore,
    StateNotPersistedError,
    compute_checksum,
)
from sidecar.models import AlertEnvelope, TriggerType, TriageState


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


def _decision(alert_id: str = "alert-1", approver: str = "approver-a") -> DecisionMessage:
    return DecisionMessage(
        alertId=alert_id,
        approverId=approver,
        decision=ControlTowerDecision.APPROVE,
        stepUpAuthenticated=True,
    )


# --- checkpoint store -------------------------------------------------------


def test_persist_confirms_durable_and_round_trips() -> None:
    store = InMemoryCheckpointStore()
    state = _state()

    confirmation = store.persist(state)
    assert confirmation.durable is True
    assert confirmation.checksum is not None
    assert store.has_checkpoint("alert-1")
    assert store.validate_integrity("alert-1")

    restored = store.retrieve("alert-1")
    assert restored is not None
    assert restored.alertId == "alert-1"
    assert restored == state


def test_persist_failure_mode_reports_non_durable_and_stores_nothing() -> None:
    store = InMemoryCheckpointStore(fail_persist=True)
    confirmation = store.persist(_state())
    assert confirmation.durable is False
    assert store.has_checkpoint("alert-1") is False


def test_corrupt_makes_integrity_validation_fail() -> None:
    store = InMemoryCheckpointStore()
    store.persist(_state())
    assert store.validate_integrity("alert-1")

    assert store.corrupt("alert-1") is True
    assert store.validate_integrity("alert-1") is False


def test_integrity_and_retrieve_on_unknown_alert() -> None:
    store = InMemoryCheckpointStore()
    assert store.has_checkpoint("nope") is False
    assert store.validate_integrity("nope") is False
    assert store.retrieve("nope") is None


def test_checksum_is_content_addressed() -> None:
    assert compute_checksum("abc") == compute_checksum("abc")
    assert compute_checksum("abc") != compute_checksum("abd")


# --- persist-then-pause (R11.1, R11.7) --------------------------------------


def test_escalate_and_pause_persists_then_pauses() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)

    handle = manager.escalate_and_pause(_state())
    assert handle.paused is True
    assert handle.alertId == "alert-1"
    assert manager.is_paused("alert-1")
    # Retained while paused (R11.2).
    assert "alert-1" in store.retained_alert_ids


def test_persistence_failure_neither_pauses_nor_advances() -> None:
    store = InMemoryCheckpointStore(fail_persist=True)
    manager = HITLManager(store)
    state = _state()

    with pytest.raises(StateNotPersistedError) as excinfo:
        manager.escalate_and_pause(state)

    assert excinfo.value.alert_id == "alert-1"
    # Not paused, nothing retained; pre-escalation state unchanged (R11.7).
    assert manager.is_paused("alert-1") is False
    assert store.has_checkpoint("alert-1") is False
    assert state.escalated is True  # caller's state object is untouched by the manager


def test_persistence_failure_wraps_store_errors() -> None:
    class _BoomStore(InMemoryCheckpointStore):
        def persist(self, state):  # type: ignore[override]
            raise OSError("disk gone")

    manager = HITLManager(_BoomStore())
    with pytest.raises(StateNotPersistedError):
        manager.escalate_and_pause(_state())


# --- resume-with-integrity (R11.2, R11.3, R11.8) ----------------------------


def test_happy_resume_returns_restored_state() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    state = _state()
    manager.escalate_and_pause(state)

    restored = manager.resume("alert-1", _decision())
    assert restored == state
    assert manager.is_paused("alert-1") is False
    # The decision was recorded (R11.2).
    assert len(manager.recorded_decisions("alert-1")) == 1


def test_resume_with_corrupt_checkpoint_stays_paused_and_raises() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    manager.escalate_and_pause(_state())

    store.corrupt("alert-1")
    with pytest.raises(CheckpointNotRestoredError):
        manager.resume("alert-1", _decision())

    # Still paused; did not resume (R11.8).
    assert manager.is_paused("alert-1") is True


def test_resume_with_missing_checkpoint_stays_paused_and_raises() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    manager.escalate_and_pause(_state())

    store.drop("alert-1")
    with pytest.raises(CheckpointNotRestoredError):
        manager.resume("alert-1", _decision())

    assert manager.is_paused("alert-1") is True


def test_retained_until_decision_then_release() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    manager.escalate_and_pause(_state())

    # Retained while paused, no decision yet (R11.2).
    assert "alert-1" in store.retained_alert_ids
    assert manager.recorded_decisions("alert-1") == []

    manager.resume("alert-1", _decision())
    # Decision recorded; retention obligation satisfied. Release frees the slot.
    store.release("alert-1")
    assert "alert-1" not in store.retained_alert_ids
