"""Unit tests for task 2.2: composite models and graph state."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from sidecar.models import (
    AlertEnvelope,
    DeniedInvocation,
    Evidence_Item,
    Gap,
    Hypothesis,
    Recommended_Action,
    ThreatCategory,
    TriageState,
    Triage_Report,
    TriggerType,
    Verdict,
    VerdictValue,
)


# --- helpers --------------------------------------------------------------


def _evidence(**overrides) -> Evidence_Item:
    kwargs = {
        "auditRecordId": "evt-1",
        "kind": "context",
        "summary": "session opened from new asn",
    }
    kwargs.update(overrides)
    return Evidence_Item(**kwargs)


# --- AlertEnvelope --------------------------------------------------------


def test_alert_envelope_valid_minimal():
    env = AlertEnvelope(
        schemaVersion="1.0",
        alertId="a-1",
        triggerType=TriggerType.CANARY_TOKEN,
        alertTimestampMs=1_700_000_000_000,
        signalPayload={"token": "x"},
    )
    assert env.alertId == "a-1"
    assert env.triggerType is TriggerType.CANARY_TOKEN
    assert env.actorId is None
    assert env.sessionId is None
    assert env.divergenceScore is None


def test_alert_envelope_is_frozen():
    env = AlertEnvelope(
        schemaVersion="1.0",
        alertId="a-1",
        triggerType=TriggerType.CANARY_TOKEN,
        alertTimestampMs=1,
        signalPayload={},
    )
    with pytest.raises(ValidationError):
        env.alertId = "a-2"  # type: ignore[misc]


def test_alert_envelope_rejects_empty_alert_id():
    with pytest.raises(ValidationError):
        AlertEnvelope(
            schemaVersion="1.0",
            alertId="",
            triggerType=TriggerType.CANARY_TOKEN,
            alertTimestampMs=1,
            signalPayload={},
        )


def test_alert_envelope_rejects_unknown_trigger():
    with pytest.raises(ValidationError):
        AlertEnvelope(
            schemaVersion="1.0",
            alertId="a-1",
            triggerType="not-a-trigger",
            alertTimestampMs=1,
            signalPayload={},
        )


def test_alert_envelope_forbids_extra():
    with pytest.raises(ValidationError):
        AlertEnvelope(
            schemaVersion="1.0",
            alertId="a-1",
            triggerType=TriggerType.CANARY_TOKEN,
            alertTimestampMs=1,
            signalPayload={},
            nope=1,
        )


# --- Evidence_Item --------------------------------------------------------


def test_evidence_item_requires_audit_record_id():
    with pytest.raises(ValidationError):
        Evidence_Item(kind="context", summary="s")  # type: ignore[call-arg]


def test_evidence_item_rejects_empty_audit_record_id():
    with pytest.raises(ValidationError):
        _evidence(auditRecordId="")


def test_evidence_item_defaults_untrusted_true():
    assert _evidence().sourceContentUntrusted is True


def test_evidence_item_is_frozen():
    item = _evidence()
    with pytest.raises(ValidationError):
        item.summary = "changed"  # type: ignore[misc]


# --- Hypothesis -----------------------------------------------------------


def test_hypothesis_valid():
    hyp = Hypothesis(
        id="h-1",
        statement="actor session hijacked",
        threatCategory=ThreatCategory.SESSION_HIJACK,
        supportingEvidence=[_evidence()],
    )
    assert hyp.resolved is False
    assert len(hyp.supportingEvidence) == 1


def test_hypothesis_requires_at_least_one_evidence():
    with pytest.raises(ValidationError):
        Hypothesis(
            id="h-1",
            statement="x",
            threatCategory=ThreatCategory.BENIGN_ANOMALY,
            supportingEvidence=[],
        )


def test_hypothesis_resolved_is_mutable():
    hyp = Hypothesis(
        id="h-1",
        statement="x",
        threatCategory=ThreatCategory.BENIGN_ANOMALY,
        supportingEvidence=[_evidence()],
    )
    hyp.resolved = True
    assert hyp.resolved is True


def test_hypothesis_rejects_unknown_category():
    with pytest.raises(ValidationError):
        Hypothesis(
            id="h-1",
            statement="x",
            threatCategory="unknown",
            supportingEvidence=[_evidence()],
        )


# --- Verdict --------------------------------------------------------------


def test_verdict_valid():
    v = Verdict(
        value=VerdictValue.CONFIRMED_THREAT,
        confidence=0.8,
        threatCategory=ThreatCategory.PROMPT_INJECTION,
    )
    assert v.confidence == 0.8
    assert v.malformedRejected is False


@pytest.mark.parametrize(
    "raw,expected",
    [(-0.5, 0.0), (0.0, 0.0), (0.5, 0.5), (1.0, 1.0), (2.5, 1.0)],
)
def test_verdict_clamps_confidence(raw, expected):
    v = Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=raw,
        threatCategory=ThreatCategory.FALSE_POSITIVE,
    )
    assert v.confidence == expected


def test_verdict_is_frozen():
    v = Verdict(
        value=VerdictValue.BENIGN,
        confidence=0.1,
        threatCategory=ThreatCategory.BENIGN_ANOMALY,
    )
    with pytest.raises(ValidationError):
        v.confidence = 0.9  # type: ignore[misc]


# --- Recommended_Action ---------------------------------------------------


def test_recommended_action_valid_and_unexecuted():
    action = Recommended_Action(
        description="isolate session",
        targetsProtectedState=True,
    )
    assert action.executed is False


def test_recommended_action_cannot_be_executed_true():
    with pytest.raises(ValidationError):
        Recommended_Action(
            description="isolate session",
            targetsProtectedState=True,
            executed=True,
        )


# --- Triage_Report --------------------------------------------------------


def test_triage_report_valid():
    report = Triage_Report(
        alertId="a-1",
        triggerType=TriggerType.SESSION_HIJACK,
        verdict=VerdictValue.CONFIRMED_THREAT,
        confidence=0.9,
        threatCategory=ThreatCategory.SESSION_HIJACK,
        evidence=[_evidence()],
        schemaVersion="1.0",
    )
    assert report.noEvidenceFlag is False
    assert report.excludedEvidence == []
    assert report.recommendedAction is None


def test_triage_report_clamps_confidence():
    report = Triage_Report(
        alertId="a-1",
        triggerType=TriggerType.MONITORING_GAP,
        verdict=VerdictValue.UNCERTAIN,
        confidence=5.0,
        threatCategory=ThreatCategory.FALSE_POSITIVE,
        evidence=[],
        schemaVersion="1.0",
    )
    assert report.confidence == 1.0


def test_triage_report_is_frozen():
    report = Triage_Report(
        alertId="a-1",
        triggerType=TriggerType.CANARY_TOKEN,
        verdict=VerdictValue.BENIGN,
        confidence=0.2,
        threatCategory=ThreatCategory.BENIGN_ANOMALY,
        evidence=[],
        schemaVersion="1.0",
    )
    with pytest.raises(ValidationError):
        report.confidence = 0.5  # type: ignore[misc]


# --- TriageState ----------------------------------------------------------


def test_triage_state_minimal_defaults():
    state = TriageState(alertId="a-1", investigation_started_ms=1_700_000_000_000)
    assert state.envelope is None
    assert state.triggerType is None
    assert state.context == []
    assert state.correlations == []
    assert state.hypotheses == []
    assert state.evidence == []
    assert state.gaps == []
    assert state.probe_steps_used == 0
    assert state.probe_started_ms is None
    assert state.verdict is None
    assert state.recommended_action is None
    assert state.escalated is False
    assert state.denied_invocations == []


def test_triage_state_lists_are_independent():
    s1 = TriageState(alertId="a-1", investigation_started_ms=1)
    s2 = TriageState(alertId="a-2", investigation_started_ms=2)
    s1.gaps.append(Gap(stage="context", element="e", reason="r"))
    assert s2.gaps == []


def test_triage_state_is_mutable_and_validates_assignment():
    state = TriageState(alertId="a-1", investigation_started_ms=1)
    state.escalated = True
    state.probe_steps_used = 3
    state.verdict = Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=0.0,
        threatCategory=ThreatCategory.FALSE_POSITIVE,
    )
    state.denied_invocations.append(
        DeniedInvocation(requestedTool="t", source="s", reason="r")
    )
    assert state.escalated is True
    assert state.probe_steps_used == 3
    assert state.verdict is not None

    with pytest.raises(ValidationError):
        state.triggerType = "bogus"  # type: ignore[assignment]


def test_triage_state_requires_investigation_started_ms():
    with pytest.raises(ValidationError):
        TriageState(alertId="a-1")  # type: ignore[call-arg]


def test_triage_state_forbids_extra():
    with pytest.raises(ValidationError):
        TriageState(alertId="a-1", investigation_started_ms=1, nope=True)
