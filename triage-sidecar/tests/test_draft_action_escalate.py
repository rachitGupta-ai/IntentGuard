"""Unit tests for the draft_action / escalate nodes (task 16.1, R1.1/R1.4/R1.5/R1.6, R9.6).

These tests exercise the advisory boundary at the sharp end of the graph:

  * a confident verdict drafts exactly ONE unexecuted Recommended_Action and
    routes to emit_report (R1.4);
  * a confirmed-threat action targets protected state and is routed to the
    Control_Tower, while the sidecar leaves the state unchanged (R1.5, R1.6);
  * benign / false-positive actions target no protected state;
  * a non-confident (uncertain / malformed / missing) verdict drafts NO
    autonomous action and escalates instead (R9.6);
  * escalate is idempotent, clears any autonomous action for uncertain verdicts,
    invokes the HITL hook seam, and routes to emit_report.

No engine I/O, no tools, no time — the nodes are pure state transitions.
"""

from __future__ import annotations

from typing import Optional

import pytest

from sidecar.models import (
    Recommended_Action,
    ThreatCategory,
    TriageState,
    TriggerType,
    Verdict,
    VerdictValue,
)
from sidecar.triage.nodes.draft_action import (
    ROUTE_EMIT_REPORT,
    ROUTE_ESCALATE,
    ActionDraft,
    DefaultActionDrafter,
    DraftActionResult,
    draft_action,
    route_after_draft_action,
)
from sidecar.triage.nodes.escalate import (
    EscalateResult,
    escalate,
    route_after_escalate,
)


# --- helpers --------------------------------------------------------------


def _state(verdict: Optional[Verdict] = None, *, alert_id: str = "alert-1") -> TriageState:
    return TriageState(
        alertId=alert_id,
        triggerType=TriggerType.BLOCK_RANGE_DIVERGENCE,
        verdict=verdict,
        investigation_started_ms=0,
    )


def _verdict(
    value: VerdictValue,
    *,
    category: ThreatCategory = ThreatCategory.SESSION_HIJACK,
    confidence: float = 0.9,
    malformed: bool = False,
) -> Verdict:
    return Verdict(
        value=value,
        confidence=confidence,
        threatCategory=category,
        malformedRejected=malformed,
    )


# --- draft_action: confident verdict drafts exactly one action (R1.4) -----


def test_confident_confirmed_threat_drafts_one_unexecuted_action():
    state = _state(_verdict(VerdictValue.CONFIRMED_THREAT))

    result = draft_action(state)

    assert isinstance(result, DraftActionResult)
    assert result.next == ROUTE_EMIT_REPORT
    assert result.drafted is True
    action = result.action
    assert isinstance(action, Recommended_Action)
    # R1.4/R1.6: never executed (Literal[False]).
    assert action.executed is False
    # Exactly one action stored on state.
    assert state.recommended_action is action


def test_confirmed_threat_targets_protected_state_and_routes_to_control_tower():
    """A containment action targets protected state -> Control_Tower (R1.5)."""

    state = _state(_verdict(VerdictValue.CONFIRMED_THREAT))

    result = draft_action(state)

    assert result.action.targetsProtectedState is True
    assert result.routes_to_control_tower is True
    # The sidecar leaves protected state unchanged: it only drafts (advisory).
    assert result.action.executed is False


@pytest.mark.parametrize(
    "value",
    [VerdictValue.BENIGN, VerdictValue.FALSE_POSITIVE],
)
def test_benign_and_false_positive_do_not_target_protected_state(value):
    state = _state(_verdict(value, category=ThreatCategory.BENIGN_ANOMALY))

    result = draft_action(state)

    assert result.drafted is True
    assert result.action.targetsProtectedState is False
    assert result.routes_to_control_tower is False
    assert result.next == ROUTE_EMIT_REPORT
    assert result.action.executed is False


def test_action_description_references_alert_and_category():
    state = _state(
        _verdict(VerdictValue.CONFIRMED_THREAT, category=ThreatCategory.PROMPT_INJECTION),
        alert_id="alert-42",
    )

    result = draft_action(state)

    assert "alert-42" in result.action.description
    assert "prompt-injection" in result.action.description


# --- draft_action: non-confident verdicts never draft (R9.6) --------------


def test_uncertain_verdict_drafts_no_action_and_escalates():
    state = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))

    result = draft_action(state)

    assert result.drafted is False
    assert result.action is None
    assert result.next == ROUTE_ESCALATE
    assert state.escalated is True
    assert state.recommended_action is None


def test_malformed_rejected_verdict_drafts_no_action_and_escalates():
    state = _state(
        _verdict(VerdictValue.CONFIRMED_THREAT, malformed=True)
    )

    result = draft_action(state)

    assert result.drafted is False
    assert result.next == ROUTE_ESCALATE
    assert state.escalated is True


def test_missing_verdict_drafts_no_action_and_escalates():
    state = _state(None)

    result = draft_action(state)

    assert result.drafted is False
    assert result.next == ROUTE_ESCALATE
    assert state.escalated is True


def test_route_after_draft_action_mirrors_state():
    confident = _state(_verdict(VerdictValue.BENIGN))
    draft_action(confident)
    assert route_after_draft_action(confident) == ROUTE_EMIT_REPORT

    uncertain = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))
    draft_action(uncertain)
    assert route_after_draft_action(uncertain) == ROUTE_ESCALATE


# --- draft_action: injectable drafter -------------------------------------


class _StubDrafter:
    def __init__(self, draft: ActionDraft) -> None:
        self._draft = draft
        self.calls = 0

    def draft(self, state, verdict) -> ActionDraft:
        self.calls += 1
        return self._draft


def test_injected_drafter_is_used():
    stub = _StubDrafter(ActionDraft(description="custom advice", targetsProtectedState=False))
    state = _state(_verdict(VerdictValue.CONFIRMED_THREAT))

    result = draft_action(state, drafter=stub)

    assert stub.calls == 1
    assert result.action.description == "custom advice"
    assert result.action.targetsProtectedState is False
    assert result.action.executed is False


def test_default_drafter_is_deterministic():
    drafter = DefaultActionDrafter()
    state = _state(_verdict(VerdictValue.CONFIRMED_THREAT))
    verdict = state.verdict

    first = drafter.draft(state, verdict)
    second = drafter.draft(state, verdict)

    assert first == second
    assert first.targetsProtectedState is True


# --- escalate node --------------------------------------------------------


def test_escalate_marks_state_and_routes_to_emit_report():
    state = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))

    result = escalate(state)

    assert isinstance(result, EscalateResult)
    assert state.escalated is True
    assert result.next == ROUTE_EMIT_REPORT
    assert route_after_escalate(state) == ROUTE_EMIT_REPORT


def test_escalate_is_idempotent():
    state = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))
    escalate(state)
    escalate(state)
    assert state.escalated is True


def test_escalate_clears_autonomous_action_for_uncertain_verdict():
    """R9.6: an uncertain verdict must not carry an autonomous action."""

    state = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))
    # Simulate a stray action that must not survive escalation.
    state.recommended_action = Recommended_Action(
        description="stray", targetsProtectedState=False, executed=False
    )

    escalate(state)

    assert state.recommended_action is None


def test_escalate_clears_autonomous_action_when_verdict_missing():
    state = _state(None)
    state.recommended_action = Recommended_Action(
        description="stray", targetsProtectedState=True, executed=False
    )

    escalate(state)

    assert state.recommended_action is None


def test_escalate_invokes_hitl_hook():
    state = _state(_verdict(VerdictValue.UNCERTAIN, confidence=0.0))
    seen = []

    class _Hook:
        def on_escalate(self, s: TriageState) -> None:
            seen.append(s.alertId)

    escalate(state, hook=_Hook())

    assert seen == ["alert-1"]


def test_escalate_does_not_clear_confident_action():
    """A confident verdict routed here (e.g. by a guard) keeps its action.

    escalate only strips autonomous actions for uncertain/missing verdicts; it
    must not silently drop a legitimately drafted action for a confident verdict.
    """

    state = _state(_verdict(VerdictValue.CONFIRMED_THREAT))
    action = Recommended_Action(
        description="contain", targetsProtectedState=True, executed=False
    )
    state.recommended_action = action

    escalate(state)

    assert state.recommended_action is action
