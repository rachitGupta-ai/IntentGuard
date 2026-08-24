"""Unit tests for task 14.1: the ``synthesize_verdict`` Investigation_Graph node.

These exercise the deterministic verdict-model double across the two-stage guard
(schema validation + clamping, then independent malformed classification) and
the fail-open-to-human routing (R9.1-R9.6):

  * well-formed confident verdict -> draft_action;
  * confidence clamping to [0.0, 1.0] (R9.2);
  * independent malformed classification even when schema would pass (R9.3);
  * out-of-set value / category, missing / non-numeric confidence, absent
    required field, non-mapping output -> forced uncertain + escalate (R9.3/R9.4);
  * legitimately-uncertain model verdict escalates and drafts no action (R9.6);
  * model error / timeout / empty output -> forced uncertain + escalate (R9.4).
"""

from __future__ import annotations

from typing import Any

from sidecar.models import (
    Evidence_Item,
    Hypothesis,
    ThreatCategory,
    TriageState,
    TriggerType,
    Verdict,
    VerdictValue,
)
from sidecar.tools.timeout import InlineTimeoutRunner, TimeoutRunner, ToolTimeout
from sidecar.triage.nodes.synthesize_verdict import (
    MALFORMED_FALLBACK_THREAT_CATEGORY,
    ROUTE_DRAFT_ACTION,
    ROUTE_ESCALATE,
    STAGE_VERDICT,
    StaticVerdictModel,
    SynthesizeVerdictResult,
    VerdictRequest,
    route_after_synthesize_verdict,
    synthesize_verdict,
)


# --- helpers --------------------------------------------------------------


def _state() -> TriageState:
    return TriageState(
        alertId="alert-1",
        triggerType=TriggerType.SESSION_HIJACK,
        investigation_started_ms=0,
    )


def _raw(
    *,
    value: Any = "confirmed_threat",
    confidence: Any = 0.87,
    threatCategory: Any = "session-hijack",
) -> dict[str, Any]:
    return {
        "value": value,
        "confidence": confidence,
        "threatCategory": threatCategory,
    }


class _AlwaysTimeoutRunner:
    """A :class:`TimeoutRunner` double that always raises :class:`ToolTimeout`."""

    def run(self, func, timeout_seconds):  # noqa: ANN001, D401
        raise ToolTimeout(f"forced timeout after {timeout_seconds}s")


_RUNNER: TimeoutRunner = InlineTimeoutRunner()


# --- well-formed confident verdict -> draft_action ------------------------


def test_confident_verdict_routes_to_draft_action():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(_raw()), runner=_RUNNER)

    assert isinstance(result, SynthesizeVerdictResult)
    assert result.route == ROUTE_DRAFT_ACTION
    assert result.escalated is False
    assert result.malformed is False
    assert result.verdict.value is VerdictValue.CONFIRMED_THREAT
    assert result.verdict.confidence == 0.87
    assert result.verdict.threatCategory is ThreatCategory.SESSION_HIJACK
    assert result.verdict.malformedRejected is False
    # Recorded into state; not escalated; no gap recorded.
    assert state.verdict == result.verdict
    assert state.escalated is False
    assert state.gaps == []


def test_all_non_uncertain_values_route_to_draft_action():
    for value in (VerdictValue.CONFIRMED_THREAT, VerdictValue.BENIGN, VerdictValue.FALSE_POSITIVE):
        state = _state()
        result = synthesize_verdict(
            state, StaticVerdictModel(_raw(value=value.value)), runner=_RUNNER
        )
        assert result.route == ROUTE_DRAFT_ACTION
        assert result.verdict.value is value


def test_accepts_enum_typed_raw_fields():
    state = _state()
    raw = _raw(value=VerdictValue.BENIGN, threatCategory=ThreatCategory.BENIGN_ANOMALY)
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_DRAFT_ACTION
    assert result.verdict.value is VerdictValue.BENIGN


# --- confidence clamping (R9.2) -------------------------------------------


def test_confidence_above_one_is_clamped():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(_raw(confidence=1.5)), runner=_RUNNER)
    assert result.route == ROUTE_DRAFT_ACTION
    assert result.verdict.confidence == 1.0


def test_confidence_below_zero_is_clamped():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(_raw(confidence=-3.0)), runner=_RUNNER)
    assert result.route == ROUTE_DRAFT_ACTION
    assert result.verdict.confidence == 0.0


def test_integer_confidence_is_numeric_and_accepted():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(_raw(confidence=1)), runner=_RUNNER)
    assert result.route == ROUTE_DRAFT_ACTION
    assert result.verdict.confidence == 1.0


# --- legitimately-uncertain verdict (R9.6) --------------------------------


def test_uncertain_verdict_escalates_without_drafting_action():
    state = _state()
    raw = _raw(value="uncertain", confidence=0.4)
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)

    assert result.route == ROUTE_ESCALATE
    assert result.escalated is True
    # A well-formed uncertain verdict is NOT flagged as malformed.
    assert result.malformed is False
    assert result.verdict.value is VerdictValue.UNCERTAIN
    assert result.verdict.malformedRejected is False
    assert state.escalated is True
    assert state.recommended_action is None


# --- independent malformed classification (R9.3) --------------------------


def test_out_of_set_value_forces_uncertain_and_escalates():
    state = _state()
    raw = _raw(value="totally_bogus")
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)

    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert result.verdict.value is VerdictValue.UNCERTAIN
    assert result.verdict.confidence == 0.0
    assert result.verdict.malformedRejected is True
    assert result.verdict.threatCategory is MALFORMED_FALLBACK_THREAT_CATEGORY
    assert state.escalated is True
    assert any(g.stage == STAGE_VERDICT for g in state.gaps)
    assert any("value outside the permitted set" in g.reason for g in state.gaps)


def test_out_of_set_threat_category_forces_uncertain():
    state = _state()
    raw = _raw(threatCategory="not-a-category")
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("threatCategory outside the permitted set" in g.reason for g in state.gaps)


def test_missing_confidence_forces_uncertain():
    state = _state()
    raw = {"value": "benign", "threatCategory": "benign-anomaly"}
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("required field(s) absent" in g.reason for g in state.gaps)


def test_non_numeric_confidence_forces_uncertain():
    state = _state()
    raw = _raw(confidence="high")
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("missing or non-numeric" in g.reason for g in state.gaps)


def test_boolean_confidence_is_rejected_as_non_numeric():
    state = _state()
    raw = _raw(confidence=True)
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True


def test_absent_required_field_forces_uncertain():
    state = _state()
    raw = {"confidence": 0.5, "threatCategory": "benign-anomaly"}  # no "value"
    result = synthesize_verdict(state, StaticVerdictModel(raw), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("value" in g.reason for g in state.gaps)


def test_non_mapping_output_forces_uncertain():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel("garbage"), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("not a mapping" in g.reason for g in state.gaps)


# --- model failure modes (R9.4) -------------------------------------------


def test_empty_output_forces_uncertain_and_escalates():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(None), runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("returned no verdict" in g.reason for g in state.gaps)


def test_model_error_forces_uncertain_and_escalates():
    state = _state()
    model = StaticVerdictModel(error=RuntimeError("boom"))
    result = synthesize_verdict(state, model, runner=_RUNNER)
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("language model error" in g.reason for g in state.gaps)


def test_timeout_forces_uncertain_and_escalates():
    state = _state()
    result = synthesize_verdict(state, StaticVerdictModel(_raw()), runner=_AlwaysTimeoutRunner())
    assert result.route == ROUTE_ESCALATE
    assert result.malformed is True
    assert any("timeout" in g.reason for g in state.gaps)


# --- routing helper -------------------------------------------------------


def test_route_after_synthesize_verdict_mirrors_state():
    escalated = _state()
    synthesize_verdict(escalated, StaticVerdictModel(_raw(value="uncertain")), runner=_RUNNER)
    assert route_after_synthesize_verdict(escalated) == ROUTE_ESCALATE

    confident = _state()
    synthesize_verdict(confident, StaticVerdictModel(_raw()), runner=_RUNNER)
    assert route_after_synthesize_verdict(confident) == ROUTE_DRAFT_ACTION


# --- request construction -------------------------------------------------


def test_request_from_state_carries_analysis_context():
    state = _state()
    evidence = Evidence_Item(
        auditRecordId="evt-1", kind="context", summary="login from new geo"
    )
    state.context = [evidence]
    state.evidence = [evidence]
    state.hypotheses = [
        Hypothesis(
            id="h1",
            statement="session hijacked",
            threatCategory=ThreatCategory.SESSION_HIJACK,
            supportingEvidence=[evidence],
        )
    ]

    request = VerdictRequest.from_state(state)

    assert request.alertId == "alert-1"
    assert request.triggerType == TriggerType.SESSION_HIJACK.value
    assert request.context == (evidence,)
    assert request.evidence == (evidence,)
    assert request.hypothesisStatements == ("session hijacked",)
