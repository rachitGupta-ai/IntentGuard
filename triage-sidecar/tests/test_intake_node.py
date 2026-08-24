"""Unit tests for task 9.1: the ``intake_validate`` Investigation_Graph node.

Covers R4.1 (validate before any tool use), R4.4 (record validated envelope +
trigger type on success and route to gather_context), and R4.2 (invalid envelope
halts, sets an uncertain verdict referencing the alertId + failed check, and
routes to escalate).
"""

from __future__ import annotations

import pytest

from sidecar.config import SidecarConfig
from sidecar.models import AlertEnvelope, TriageState, TriggerType, VerdictValue
from sidecar.triage.nodes.intake import (
    INTAKE_FAILURE_THREAT_CATEGORY,
    INTAKE_STAGE,
    ROUTE_ESCALATE,
    ROUTE_GATHER_CONTEXT,
    intake_validate,
    route_after_intake,
)


# --- helpers --------------------------------------------------------------


def _state(alert_id: str = "alert-1") -> TriageState:
    return TriageState(alertId=alert_id, investigation_started_ms=0)


def _valid_payload(alert_id: str = "alert-1", version: str = "v1") -> dict:
    return {
        "schemaVersion": version,
        "alertId": alert_id,
        "triggerType": TriggerType.BLOCK_RANGE_DIVERGENCE.value,
        "actorId": "actor-7",
        "sessionId": "sess-3",
        "alertTimestampMs": 1_000,
        "divergenceScore": 0.97,
        "signalPayload": {"score": 0.97},
    }


def _valid_envelope(alert_id: str = "alert-1", version: str = "v1") -> AlertEnvelope:
    return AlertEnvelope.model_validate(_valid_payload(alert_id, version))


# --- success path (R4.1, R4.4) -------------------------------------------


def test_valid_raw_mapping_records_envelope_and_routes_to_gather_context():
    state = _state()
    result = intake_validate(state, _valid_payload())

    assert result.valid is True
    assert result.next == ROUTE_GATHER_CONTEXT
    assert result.failedCheck is None
    # Validated envelope + trigger type recorded before context gathering (R4.4).
    assert isinstance(result.state.envelope, AlertEnvelope)
    assert result.state.envelope.alertId == "alert-1"
    assert result.state.triggerType == TriggerType.BLOCK_RANGE_DIVERGENCE
    # Success path does not escalate and records no verdict/gap.
    assert result.state.escalated is False
    assert result.state.verdict is None
    assert result.state.gaps == []


def test_valid_already_parsed_envelope_is_accepted():
    state = _state()
    result = intake_validate(state, _valid_envelope())

    assert result.valid is True
    assert result.next == ROUTE_GATHER_CONTEXT
    assert result.state.envelope is not None
    assert result.state.triggerType == TriggerType.BLOCK_RANGE_DIVERGENCE


def test_valid_envelope_falls_back_to_state_envelope_when_arg_absent():
    state = _state()
    state.envelope = _valid_envelope()
    result = intake_validate(state)

    assert result.valid is True
    assert result.next == ROUTE_GATHER_CONTEXT
    assert result.state.triggerType == TriggerType.BLOCK_RANGE_DIVERGENCE


@pytest.mark.parametrize("trigger", list(TriggerType))
def test_all_trigger_types_are_accepted(trigger: TriggerType):
    state = _state()
    payload = _valid_payload()
    payload["triggerType"] = trigger.value
    result = intake_validate(state, payload)

    assert result.valid is True
    assert result.state.triggerType == trigger


# --- failure path (R4.2) --------------------------------------------------


def _assert_escalated_uncertain(result, *, alert_id: str) -> None:
    """Shared assertions for an invalid-envelope escalation outcome."""
    assert result.valid is False
    assert result.next == ROUTE_ESCALATE
    assert result.escalated is True
    assert result.failedCheck  # non-empty description of the failed check

    state = result.state
    # Halt-and-escalate: uncertain verdict with confidence 0.0 (R4.2).
    assert state.escalated is True
    assert state.verdict is not None
    assert state.verdict.value == VerdictValue.UNCERTAIN
    assert state.verdict.confidence == 0.0
    assert state.verdict.threatCategory == INTAKE_FAILURE_THREAT_CATEGORY
    assert state.verdict.malformedRejected is True
    # No context recorded, no envelope promoted.
    assert state.envelope is None
    assert state.triggerType is None
    # A single intake gap naming the failed check and the alertId (R4.2).
    assert len(state.gaps) == 1
    gap = state.gaps[0]
    assert gap.stage == INTAKE_STAGE
    assert alert_id in gap.element
    assert gap.reason == result.failedCheck


def test_missing_required_field_halts_and_escalates():
    state = _state()
    payload = _valid_payload()
    del payload["alertTimestampMs"]  # required field
    result = intake_validate(state, payload)

    _assert_escalated_uncertain(result, alert_id="alert-1")
    assert "alertTimestampMs" in result.failedCheck


def test_unrecognized_trigger_type_halts_and_escalates():
    state = _state()
    payload = _valid_payload()
    payload["triggerType"] = "NOT_A_TRIGGER"
    result = intake_validate(state, payload)

    _assert_escalated_uncertain(result, alert_id="alert-1")
    assert "triggerType" in result.failedCheck


def test_unsupported_schema_version_halts_and_escalates():
    state = _state()
    payload = _valid_payload(version="v99")
    result = intake_validate(state, payload)

    _assert_escalated_uncertain(result, alert_id="alert-1")
    assert "schemaVersion" in result.failedCheck


def test_alert_id_mismatch_halts_and_escalates():
    state = _state(alert_id="run-alert")
    payload = _valid_payload(alert_id="different-alert")
    result = intake_validate(state, payload)

    _assert_escalated_uncertain(result, alert_id="run-alert")
    assert "mismatch" in result.failedCheck


def test_missing_envelope_entirely_halts_and_escalates():
    state = _state()
    result = intake_validate(state, None)

    _assert_escalated_uncertain(result, alert_id="alert-1")
    assert "missing envelope" == result.failedCheck


def test_empty_alert_id_in_payload_halts_and_escalates():
    state = _state()
    payload = _valid_payload()
    payload["alertId"] = ""  # violates min_length
    result = intake_validate(state, payload)

    # alertId is empty in payload but state.alertId is "alert-1".
    _assert_escalated_uncertain(result, alert_id="alert-1")
    assert "alertId" in result.failedCheck


# --- custom config --------------------------------------------------------


def test_custom_supported_version_is_honored():
    state = _state()
    cfg = SidecarConfig(supported_schema_versions={"v2"})
    # v1 is no longer supported under this config -> escalate.
    result_v1 = intake_validate(state, _valid_payload(version="v1"), config=cfg)
    assert result_v1.next == ROUTE_ESCALATE

    # v2 is supported -> accepted.
    result_v2 = intake_validate(_state(), _valid_payload(version="v2"), config=cfg)
    assert result_v2.next == ROUTE_GATHER_CONTEXT


# --- routing helper -------------------------------------------------------


def test_route_after_intake_matches_result_next():
    ok = intake_validate(_state(), _valid_payload())
    assert route_after_intake(ok.state) == ROUTE_GATHER_CONTEXT

    bad = intake_validate(_state(), None)
    assert route_after_intake(bad.state) == ROUTE_ESCALATE
