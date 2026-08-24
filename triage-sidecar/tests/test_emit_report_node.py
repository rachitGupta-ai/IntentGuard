"""Unit tests for the terminal ``emit_report`` node (task 17.1, R10).

These exercise report assembly from a :class:`~sidecar.models.TriageState`:
verdict/confidence/threat-category population (R10.1), evidence aggregation with
record-id traceability (R10.1, R10.3), exclusion entries for unbindable items
(R10.4), the triggering alertId + trigger type (R10.6), the drafted
Recommended_Action inclusion rule (R10.2), the no-evidence flag (R10.5), schema
stamping (R14.1), the defensive ``None``-verdict fallback, and the terminal
route to the graph end.
"""

from __future__ import annotations

import pytest

from sidecar.contract import MessageStamper
from sidecar.models import (
    AlertEnvelope,
    Evidence_Item,
    Recommended_Action,
    ThreatCategory,
    TriageState,
    TriggerType,
    Verdict,
    VerdictValue,
)
from sidecar.triage.nodes.emit_report import (
    DEFAULT_UNCERTAIN_THREAT_CATEGORY,
    EMIT_REPORT_NODE,
    ROUTE_END,
    emit_report,
)

_ALERT_TS_MS = 1_700_000_000_000


def _envelope(**overrides) -> AlertEnvelope:
    base = dict(
        schemaVersion="v1",
        alertId="alert-1",
        triggerType=TriggerType.BLOCK_RANGE_DIVERGENCE,
        actorId="actor-1",
        sessionId="session-1",
        alertTimestampMs=_ALERT_TS_MS,
        signalPayload={},
    )
    base.update(overrides)
    return AlertEnvelope(**base)


def _evidence(record_id: str, *, kind: str = "context", summary: str = "fact") -> Evidence_Item:
    return Evidence_Item(auditRecordId=record_id, kind=kind, summary=summary)


def _state(**overrides) -> TriageState:
    env = overrides.pop("envelope", _envelope())
    base = dict(
        alertId="alert-1",
        envelope=env,
        triggerType=env.triggerType if env is not None else None,
        investigation_started_ms=_ALERT_TS_MS,
    )
    base.update(overrides)
    return TriageState(**base)


def _confident_verdict() -> Verdict:
    return Verdict(
        value=VerdictValue.CONFIRMED_THREAT,
        confidence=0.9,
        threatCategory=ThreatCategory.SESSION_HIJACK,
    )


# --- verdict / confidence / threat category (R10.1) -----------------------


def test_report_carries_verdict_confidence_and_threat_category():
    state = _state(verdict=_confident_verdict())

    result = emit_report(state)
    report = result.report

    assert report.verdict is VerdictValue.CONFIRMED_THREAT
    assert report.confidence == 0.9
    assert report.threatCategory is ThreatCategory.SESSION_HIJACK
    assert result.next == ROUTE_END
    assert EMIT_REPORT_NODE == "emit_report"


def test_confidence_is_a_decimal_within_unit_interval():
    # The report model clamps; a confident verdict's confidence stays in range.
    state = _state(verdict=_confident_verdict())
    report = emit_report(state).report
    assert 0.0 <= report.confidence <= 1.0


# --- alertId + trigger type (R10.6) ---------------------------------------


def test_report_includes_alert_id_and_trigger_type_from_state():
    state = _state(verdict=_confident_verdict())
    report = emit_report(state).report
    assert report.alertId == "alert-1"
    assert report.triggerType is TriggerType.BLOCK_RANGE_DIVERGENCE


def test_trigger_type_falls_back_to_envelope_when_state_field_absent():
    state = _state(verdict=_confident_verdict(), triggerType=None)
    report = emit_report(state).report
    assert report.triggerType is TriggerType.BLOCK_RANGE_DIVERGENCE


def test_missing_trigger_type_everywhere_raises():
    state = _state(verdict=_confident_verdict(), envelope=None, triggerType=None)
    with pytest.raises(ValueError):
        emit_report(state)


# --- evidence aggregation + traceability (R10.1, R10.3) -------------------


def test_evidence_aggregated_from_context_correlations_and_probe():
    state = _state(
        verdict=_confident_verdict(),
        context=[_evidence("ctx-1", summary="session")],
        correlations=[_evidence("cor-1", kind="correlation", summary="related")],
        evidence=[_evidence("prb-1", kind="probe", summary="probe")],
    )

    report = emit_report(state).report

    record_ids = {e.auditRecordId for e in report.evidence}
    assert record_ids == {"ctx-1", "cor-1", "prb-1"}
    # every included Evidence_Item carries its record id (R10.3)
    for item in report.evidence:
        assert item.auditRecordId
    assert report.noEvidenceFlag is False


def test_duplicate_evidence_across_pools_is_collapsed():
    dup = _evidence("shared-1", kind="context", summary="same fact")
    state = _state(
        verdict=_confident_verdict(),
        context=[dup],
        evidence=[_evidence("shared-1", kind="context", summary="same fact")],
    )

    report = emit_report(state).report

    matching = [e for e in report.evidence if e.auditRecordId == "shared-1"]
    assert len(matching) == 1


def test_evidence_preserves_context_then_correlation_then_probe_order():
    state = _state(
        verdict=_confident_verdict(),
        context=[_evidence("ctx-1")],
        correlations=[_evidence("cor-1", kind="correlation")],
        evidence=[_evidence("prb-1", kind="probe")],
    )
    report = emit_report(state).report
    assert [e.auditRecordId for e in report.evidence] == ["ctx-1", "cor-1", "prb-1"]


# --- no-evidence flag (R10.5) ---------------------------------------------


def test_zero_evidence_sets_flag_and_empty_collection():
    state = _state(verdict=_confident_verdict())  # no evidence pools populated
    report = emit_report(state).report
    assert report.evidence == []
    assert report.noEvidenceFlag is True


# --- recommended action inclusion (R10.2) ---------------------------------


def test_drafted_recommended_action_is_included():
    action = Recommended_Action(
        description="Notify the Control_Tower approver",
        targetsProtectedState=False,
    )
    state = _state(verdict=_confident_verdict(), recommended_action=action)
    report = emit_report(state).report
    assert report.recommendedAction == action
    assert report.recommendedAction.executed is False


def test_absent_recommended_action_is_omitted():
    state = _state(verdict=_confident_verdict())
    report = emit_report(state).report
    assert report.recommendedAction is None


# --- defensive None-verdict fallback (fail-open-to-human) -----------------


def test_none_verdict_defaults_to_uncertain_confidence_zero():
    state = _state(verdict=None)
    report = emit_report(state).report
    assert report.verdict is VerdictValue.UNCERTAIN
    assert report.confidence == 0.0
    assert report.threatCategory is DEFAULT_UNCERTAIN_THREAT_CATEGORY


def test_uncertain_verdict_from_state_is_preserved():
    verdict = Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=0.0,
        threatCategory=ThreatCategory.OFF_INTENT_AGENT,
        malformedRejected=True,
    )
    state = _state(verdict=verdict)
    report = emit_report(state).report
    assert report.verdict is VerdictValue.UNCERTAIN
    assert report.confidence == 0.0


# --- exclusion entries for unbindable items (R10.4) -----------------------


def test_bound_evidence_produces_no_exclusions():
    state = _state(
        verdict=_confident_verdict(),
        context=[_evidence("ctx-1"), _evidence("ctx-2")],
    )
    report = emit_report(state).report
    assert report.excludedEvidence == []
    assert len(report.evidence) == 2


# --- schema stamping (R14.1) ----------------------------------------------


def test_schema_version_defaults_to_outgoing_version():
    state = _state(verdict=_confident_verdict())
    report = emit_report(state).report
    assert report.schemaVersion == MessageStamper().schema_version


def test_explicit_schema_version_takes_precedence():
    state = _state(verdict=_confident_verdict())
    report = emit_report(state, schema_version="v1").report
    assert report.schemaVersion == "v1"


def test_stamper_supplies_schema_version():
    stamper = MessageStamper(schema_version="v1")
    state = _state(verdict=_confident_verdict())
    report = emit_report(state, stamper=stamper).report
    assert report.schemaVersion == "v1"


# --- node does not mutate state -------------------------------------------


def test_emit_report_does_not_mutate_state():
    state = _state(verdict=_confident_verdict(), context=[_evidence("ctx-1")])
    result = emit_report(state)
    assert result.state is state
    assert len(state.context) == 1
    assert state.verdict is not None
