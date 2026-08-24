"""Unit tests for the assembled Investigation_Graph (task 20.1).

These exercise the hand-rolled deterministic driver end to end with the existing
test doubles (``StaticEngineBackend``, ``StaticHypothesisModel``,
``StaticVerdictModel``, ``InlineTimeoutRunner``, ``ScriptedProbeStrategy``) and an
injected clock, covering:

  * a full happy-path run (intake → gather → correlate → hypotheses → probe →
    synthesize → draft_action → emit_report) producing exactly one report;
  * the intake-invalid → escalate → emit_report short circuit;
  * the total-budget overrun guard (R13.3): route to escalate with an uncertain
    verdict, retaining evidence gathered so far;
  * the unrecoverable-failure boundary (R13.4): any uncaught node exception is
    converted to uncertain + escalate and still emits one report; and
  * read-only enforcement reachable at the probe node (R1.3/R8.9/R12.5).
"""

from __future__ import annotations

from typing import Optional

import pytest

from sidecar.config import SidecarConfig
from sidecar.models import (
    AlertEnvelope,
    Evidence_Item,
    ThreatCategory,
    TriageState,
    TriggerType,
    VerdictValue,
)
from sidecar.tools import (
    EngineRecord,
    InlineTimeoutRunner,
    ReadOnlyEnforcer,
    ReadToolRegistry,
    StaticEngineBackend,
)
from sidecar.triage.graph import (
    GraphCollaborators,
    InvestigationOutcome,
    build_investigation_graph,
    run_investigation,
)
from sidecar.triage.nodes import (
    ProbeCall,
    ScriptedProbeStrategy,
    StaticHypothesisModel,
    StaticVerdictModel,
)

_ALERT_TS_MS = 1_700_000_000_000
_ALERT_ID = "alert-1"


# --- fixtures / helpers ---------------------------------------------------


def _envelope(**overrides) -> AlertEnvelope:
    base = dict(
        schemaVersion="v1",
        alertId=_ALERT_ID,
        triggerType=TriggerType.SESSION_HIJACK,
        actorId="actor-1",
        sessionId="session-1",
        alertTimestampMs=_ALERT_TS_MS,
        signalPayload={},
    )
    base.update(overrides)
    return AlertEnvelope(**base)


def _state(envelope: Optional[AlertEnvelope], **overrides) -> TriageState:
    base = dict(
        alertId=_ALERT_ID,
        envelope=envelope,
        triggerType=envelope.triggerType if envelope is not None else None,
        investigation_started_ms=_ALERT_TS_MS,
    )
    base.update(overrides)
    return TriageState(**base)


def _registry() -> ReadToolRegistry:
    """A registry over a seeded, inline (no-thread) backend so context binds."""
    backend = StaticEngineBackend(
        session_history=[EngineRecord("audit-sess-1", "session record")],
        actor_profile=[EngineRecord("audit-actor-1", "actor profile")],
        related_alerts=[EngineRecord("audit-rel-1", "related alert")],
        exfil_correlations=[EngineRecord("audit-exfil-1", "exfil correlation")],
        audit_history=[EngineRecord("audit-hist-1", "audit history")],
    )
    return ReadToolRegistry(backend, runner=InlineTimeoutRunner())


def _hypothesis_model() -> StaticHypothesisModel:
    """A model that yields one well-formed, mappable, evidence-backed hypothesis."""
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


def _collaborators(
    *,
    hypothesis_model=None,
    verdict_model=None,
    strategy=None,
    clock=None,
    config: Optional[SidecarConfig] = None,
    registry: Optional[ReadToolRegistry] = None,
    enforcer: Optional[ReadOnlyEnforcer] = None,
) -> GraphCollaborators:
    reg = registry or _registry()
    return GraphCollaborators(
        registry=reg,
        enforcer=enforcer,
        hypothesis_model=hypothesis_model or _hypothesis_model(),
        verdict_model=verdict_model or _confident_verdict_model(),
        strategy=strategy
        or ScriptedProbeStrategy(
            [
                ProbeCall(
                    tool_name="get_session_history",
                    args={"alert_id": _ALERT_ID},
                    resolve_hypotheses=("h1",),
                )
            ]
        ),
        clock=clock or _fixed_clock(),
        config=config or SidecarConfig(),
        runner=InlineTimeoutRunner(),
    )


# --- happy path -----------------------------------------------------------


def test_happy_path_produces_exactly_one_confident_report():
    """intake→...→emit_report with doubles yields one confident report."""
    state = _state(_envelope())
    outcome = build_investigation_graph(_collaborators()).run(state)

    assert isinstance(outcome, InvestigationOutcome)
    report = outcome.report
    assert report is not None
    assert report.alertId == _ALERT_ID
    assert report.triggerType is TriggerType.SESSION_HIJACK
    assert report.verdict is VerdictValue.CONFIRMED_THREAT
    assert report.confidence == 0.9
    assert report.threatCategory is ThreatCategory.SESSION_HIJACK
    # A confident verdict drafts exactly one (unexecuted) recommended action.
    assert report.recommendedAction is not None
    assert report.recommendedAction.executed is False
    assert outcome.state.escalated is False
    # Evidence gathered across gather_context / correlate is carried through.
    assert report.evidence
    assert report.schemaVersion == "v1"


def test_happy_path_visits_probe_and_resolves_hypothesis():
    state = _state(_envelope())
    outcome = build_investigation_graph(_collaborators()).run(state)

    # The probe loop ran and resolved the single hypothesis.
    assert outcome.state.probe_steps_used == 1
    assert outcome.state.hypotheses
    assert all(h.resolved for h in outcome.state.hypotheses)


def test_run_investigation_entry_accepts_an_envelope():
    """The top-level entry can start a run directly from an envelope."""
    outcome = run_investigation(
        _envelope(),
        registry=_registry(),
        hypothesis_model=_hypothesis_model(),
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
    assert outcome.report.verdict is VerdictValue.CONFIRMED_THREAT
    assert outcome.state.alertId == _ALERT_ID


# --- intake-invalid short circuit -----------------------------------------


def test_invalid_envelope_escalates_and_emits_one_uncertain_report():
    # envelope.alertId ("different-alert") != state.alertId ("alert-1"), so
    # intake validation fails before any tool runs.
    bad = _envelope(alertId="different-alert")
    state = _state(bad, alertId=_ALERT_ID, triggerType=TriggerType.SESSION_HIJACK)

    outcome = build_investigation_graph(_collaborators()).run(state)

    assert outcome.report.verdict is VerdictValue.UNCERTAIN
    assert outcome.state.escalated is True
    assert outcome.report.recommendedAction is None
    # No context tool ran on the invalid path.
    assert outcome.state.context == []


# --- total-budget guard (R13.3) -------------------------------------------


def test_budget_overrun_escalates_uncertain_and_retains_evidence():
    """Exceeding the 300s budget forces uncertain + escalate, retaining evidence."""
    # Pre-seed evidence to represent context gathered before the overrun.
    seeded = Evidence_Item(
        auditRecordId="audit-prior-1",
        kind="context",
        summary="context gathered before the budget overrun",
    )
    state = _state(_envelope(), context=[seeded])

    # Clock reports the budget already exhausted at the first transition.
    over_budget = _ALERT_TS_MS + 300 * 1000
    collaborators = _collaborators(clock=_fixed_clock(over_budget))

    outcome = build_investigation_graph(collaborators).run(state)

    assert outcome.report.verdict is VerdictValue.UNCERTAIN
    assert outcome.report.confidence == 0.0
    assert outcome.state.escalated is True
    assert outcome.report.recommendedAction is None
    # Evidence gathered so far is retained in the report (R13.3).
    assert any(e.auditRecordId == "audit-prior-1" for e in outcome.report.evidence)
    # A budget gap explains the override.
    assert any(g.stage == "budget" for g in outcome.state.gaps)


def test_run_below_budget_does_not_trigger_the_guard():
    state = _state(_envelope())
    # Elapsed is 0 (clock == investigation_started_ms), well under 300s.
    outcome = build_investigation_graph(_collaborators()).run(state)
    assert outcome.state.escalated is False
    assert all(g.stage != "budget" for g in outcome.state.gaps)


# --- unrecoverable-failure boundary (R13.4) -------------------------------


class _RaisingStrategy:
    """A probe strategy that raises an uncaught error when asked to propose."""

    def propose(self, state):  # noqa: D401 - test double
        raise RuntimeError("boom: simulated unrecoverable node failure")


def test_uncaught_exception_converts_to_uncertain_escalate_and_report():
    """Any uncaught node exception becomes uncertain + escalate + one report."""
    state = _state(_envelope())
    collaborators = _collaborators(strategy=_RaisingStrategy())

    outcome = build_investigation_graph(collaborators).run(state)

    # The failure surfaced at the probe node and was caught at the boundary.
    assert outcome.report is not None
    assert outcome.report.verdict is VerdictValue.UNCERTAIN
    assert outcome.report.confidence == 0.0
    assert outcome.state.escalated is True
    assert outcome.report.recommendedAction is None
    # A failure gap records the unrecoverable error.
    assert any(g.stage == "failure" for g in outcome.state.gaps)


def test_failure_boundary_retains_prior_evidence():
    seeded = Evidence_Item(
        auditRecordId="audit-prior-2",
        kind="context",
        summary="context gathered before the crash",
    )
    state = _state(_envelope(), context=[seeded])
    outcome = build_investigation_graph(
        _collaborators(strategy=_RaisingStrategy())
    ).run(state)
    assert any(e.auditRecordId == "audit-prior-2" for e in outcome.report.evidence)


# --- read-only enforcement reachable at any node (R1.3/R8.9/R12.5) --------


def test_out_of_set_probe_request_is_denied_and_recorded():
    """An out-of-set probe request is denied pre-execution and recorded."""
    state = _state(_envelope())
    # The probe proposes a write tool (out-of-set) then resolves the hypothesis.
    strategy = ScriptedProbeStrategy(
        [
            ProbeCall(tool_name="delete_everything", args={"target": "prod"}),
            ProbeCall(
                tool_name="get_session_history",
                args={"alert_id": _ALERT_ID},
                resolve_hypotheses=("h1",),
            ),
        ]
    )
    outcome = build_investigation_graph(
        _collaborators(strategy=strategy, config=SidecarConfig(probe_max_steps=8))
    ).run(state)

    # The out-of-set request was denied and recorded; nothing was executed.
    assert any(
        d.requestedTool == "delete_everything" for d in outcome.state.denied_invocations
    )
    assert any(e.kind == "refusal" for e in outcome.state.evidence)
    # The run still completed with exactly one report.
    assert outcome.report is not None
