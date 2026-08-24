"""Unit tests for task 12.1: the ``form_hypotheses`` Investigation_Graph node.

These exercise the deterministic hypothesis-model double across the well-formed,
unmappable, no-evidence, malformed, empty, unparseable, error, timeout, and
cap-at-10 paths (R7.1-R7.9), plus the routing convention (probe vs
synthesize_verdict).
"""

from __future__ import annotations

from typing import Any

from sidecar.models import Hypothesis, ThreatCategory, TriageState, TriggerType
from sidecar.tools.timeout import InlineTimeoutRunner, ToolTimeout, TimeoutRunner
from sidecar.triage.nodes.form_hypotheses import (
    MAX_HYPOTHESES,
    ROUTE_PROBE,
    ROUTE_SYNTHESIZE_VERDICT,
    STAGE_HYPOTHESES,
    FormHypothesesResult,
    HypothesisRequest,
    StaticHypothesisModel,
    form_hypotheses,
)


# --- helpers --------------------------------------------------------------


def _state() -> TriageState:
    return TriageState(
        alertId="alert-1",
        triggerType=TriggerType.SESSION_HIJACK,
        investigation_started_ms=0,
    )


def _raw_hypothesis(
    *,
    id: str = "h1",
    statement: str = "actor session was hijacked",
    threatCategory: str = "session-hijack",
    record_id: str | None = "evt-1",
) -> dict[str, Any]:
    support = [{"kind": "context", "summary": "login from new geo", "auditRecordId": record_id}]
    return {
        "id": id,
        "statement": statement,
        "threatCategory": threatCategory,
        "supportingEvidence": support,
    }


class _AlwaysTimeoutRunner:
    """A :class:`TimeoutRunner` double that always raises :class:`ToolTimeout`."""

    def run(self, func, timeout_seconds):  # noqa: ANN001, D401
        raise ToolTimeout(f"forced timeout after {timeout_seconds}s")


_RUNNER: TimeoutRunner = InlineTimeoutRunner()


# --- well-formed retention + routing -------------------------------------


def test_retains_well_formed_hypothesis_and_routes_to_probe():
    state = _state()
    model = StaticHypothesisModel([_raw_hypothesis()])

    result = form_hypotheses(state, model, runner=_RUNNER)

    assert isinstance(result, FormHypothesesResult)
    assert result.route == ROUTE_PROBE
    assert result.routed_to_probe is True
    assert len(result.retained) == 1
    hyp = result.retained[0]
    assert isinstance(hyp, Hypothesis)
    assert hyp.threatCategory is ThreatCategory.SESSION_HIJACK
    assert hyp.resolved is False
    assert hyp.supportingEvidence[0].auditRecordId == "evt-1"
    # Committed into state.
    assert state.hypotheses == list(result.retained)
    assert result.gaps_recorded == ()


def test_accepts_threat_category_enum_and_all_permitted_labels():
    for category in ThreatCategory:
        state = _state()
        model = StaticHypothesisModel([_raw_hypothesis(threatCategory=category.value)])
        result = form_hypotheses(state, model, runner=_RUNNER)
        assert len(result.retained) == 1
        assert result.retained[0].threatCategory is category


def test_multiple_hypotheses_all_retained():
    raws = [_raw_hypothesis(id=f"h{i}", record_id=f"evt-{i}") for i in range(3)]
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel(raws), runner=_RUNNER)
    assert len(result.retained) == 3
    assert result.route == ROUTE_PROBE


# --- discard: unmappable category (R7.5) ----------------------------------


def test_unmappable_category_discarded_with_gap():
    state = _state()
    model = StaticHypothesisModel([_raw_hypothesis(threatCategory="not-a-category")])

    result = form_hypotheses(state, model, runner=_RUNNER)

    assert result.retained == ()
    assert result.route == ROUTE_SYNTHESIZE_VERDICT
    assert len(result.gaps_recorded) == 1
    gap = result.gaps_recorded[0]
    assert gap.stage == STAGE_HYPOTHESES
    assert "Threat_Category" in gap.reason


def test_missing_category_discarded_with_gap():
    raw = _raw_hypothesis()
    del raw["threatCategory"]
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel([raw]), runner=_RUNNER)
    assert result.retained == ()
    assert len(result.gaps_recorded) == 1


# --- discard: no supporting evidence (R7.6, R7.8) -------------------------


def test_no_supporting_evidence_discarded_with_gap():
    raw = _raw_hypothesis()
    raw["supportingEvidence"] = []
    state = _state()

    result = form_hypotheses(state, StaticHypothesisModel([raw]), runner=_RUNNER)

    assert result.retained == ()
    assert result.route == ROUTE_SYNTHESIZE_VERDICT
    assert len(result.gaps_recorded) == 1
    assert "supporting Evidence_Item" in result.gaps_recorded[0].reason


def test_supporting_evidence_without_record_id_discarded():
    # Evidence present but unbindable (no record id) -> no bound support -> discard.
    raw = _raw_hypothesis(record_id=None)
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel([raw]), runner=_RUNNER)
    assert result.retained == ()
    assert len(result.gaps_recorded) >= 1


def test_partial_support_retains_hypothesis_and_records_unbound_gap():
    # One bindable + one unbindable supporting item -> hypothesis retained, but
    # the unbindable element is surfaced as a gap.
    raw = _raw_hypothesis()
    raw["supportingEvidence"] = [
        {"kind": "context", "summary": "bound fact", "auditRecordId": "evt-1"},
        {"kind": "context", "summary": "dangling fact"},
    ]
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel([raw]), runner=_RUNNER)
    assert len(result.retained) == 1
    assert len(result.retained[0].supportingEvidence) == 1
    assert any(g.element == "dangling fact" for g in result.gaps_recorded)


# --- malformed items ------------------------------------------------------


def test_non_mapping_item_discarded_with_gap():
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel(["not-a-dict"]), runner=_RUNNER)
    assert result.retained == ()
    assert any("not a mapping" in g.reason for g in result.gaps_recorded)


def test_empty_statement_discarded_with_gap():
    raw = _raw_hypothesis(statement="")
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel([raw]), runner=_RUNNER)
    assert result.retained == ()
    assert len(result.gaps_recorded) == 1


# --- model failure modes (R7.3, R7.9) -------------------------------------


def test_empty_response_records_gap_and_synthesizes():
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel([]), runner=_RUNNER)
    assert result.retained == ()
    assert result.route == ROUTE_SYNTHESIZE_VERDICT
    assert any("no hypotheses" in g.reason for g in result.gaps_recorded)


def test_none_response_records_gap():
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel(None), runner=_RUNNER)
    assert result.retained == ()
    assert len(result.gaps_recorded) == 1


def test_unparseable_response_records_gap():
    state = _state()
    # A bare string is not a valid list of hypotheses.
    result = form_hypotheses(state, StaticHypothesisModel("garbage"), runner=_RUNNER)
    assert result.retained == ()
    assert any("unparseable" in g.reason for g in result.gaps_recorded)


def test_model_error_records_gap_and_continues():
    state = _state()
    model = StaticHypothesisModel(error=RuntimeError("boom"))
    result = form_hypotheses(state, model, runner=_RUNNER)
    assert result.retained == ()
    assert result.route == ROUTE_SYNTHESIZE_VERDICT
    assert any("language model error" in g.reason for g in result.gaps_recorded)


def test_timeout_records_gap_and_continues():
    state = _state()
    model = StaticHypothesisModel([_raw_hypothesis()])
    result = form_hypotheses(state, model, runner=_AlwaysTimeoutRunner())
    assert result.retained == ()
    assert result.route == ROUTE_SYNTHESIZE_VERDICT
    assert any("timeout" in g.reason for g in result.gaps_recorded)


# --- cap at 10 (R7.1) -----------------------------------------------------


def test_caps_retained_hypotheses_at_ten():
    raws = [_raw_hypothesis(id=f"h{i}", record_id=f"evt-{i}") for i in range(15)]
    state = _state()
    result = form_hypotheses(state, StaticHypothesisModel(raws), runner=_RUNNER)
    assert len(result.retained) == MAX_HYPOTHESES
    assert result.route == ROUTE_PROBE
    assert any("beyond the cap" in g.reason for g in result.gaps_recorded)


# --- request construction -------------------------------------------------


def test_request_from_state_carries_context_and_trigger():
    state = _state()
    request = HypothesisRequest.from_state(state)
    assert request.alertId == "alert-1"
    assert request.triggerType == TriggerType.SESSION_HIJACK.value
    assert request.context == ()
    assert request.correlations == ()
