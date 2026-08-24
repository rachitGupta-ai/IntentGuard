"""Unit tests for the bounded Probe_Loop node (task 13.1, R8).

These tests exercise the probe loop with a deterministic strategy and an
injectable clock (no real time, no real engine I/O), covering:

  * step-cap termination and the failed-call-counts-a-step rule (R8.2, R8.4, R8.8),
  * wall-clock budget termination (R8.3, R8.5),
  * all-resolved early termination (R8.1, R8.7),
  * out-of-set tool rejection recorded, not executed (R8.6, R8.9),
  * no-data/error gaps recorded while probing continues (R8.8), and
  * the unconditional termination guarantee.
"""

from __future__ import annotations

from typing import Optional

import pytest

from sidecar.config import SidecarConfig
from sidecar.models import Evidence_Item, Hypothesis, ThreatCategory, TriageState
from sidecar.tools import (
    EngineRecord,
    InlineTimeoutRunner,
    ReadOnlyEnforcer,
    ReadToolRegistry,
    StaticEngineBackend,
)
from sidecar.triage.nodes.probe import (
    ROUTE_SYNTHESIZE_VERDICT,
    ProbeCall,
    ProbeResult,
    ProbeStopReason,
    ScriptedProbeStrategy,
    probe,
)

# --- helpers --------------------------------------------------------------


def _evidence(record_id: str = "audit-seed") -> Evidence_Item:
    return Evidence_Item(
        auditRecordId=record_id,
        kind="context",
        summary="seed supporting evidence",
        sourceContentUntrusted=True,
    )


def _hypothesis(hid: str, *, resolved: bool = False) -> Hypothesis:
    return Hypothesis(
        id=hid,
        statement=f"hypothesis {hid}",
        threatCategory=ThreatCategory.PROMPT_INJECTION,
        supportingEvidence=[_evidence()],
        resolved=resolved,
    )


def _state(hypotheses: list[Hypothesis], *, started_ms: Optional[int] = None) -> TriageState:
    return TriageState(
        alertId="alert-1",
        hypotheses=hypotheses,
        probe_started_ms=started_ms,
        investigation_started_ms=0,
    )


def _enforcer(
    *,
    session_history: Optional[list[EngineRecord]] = None,
) -> ReadOnlyEnforcer:
    """A ReadOnlyEnforcer over a deterministic, inline (no-thread) tool registry."""

    backend = StaticEngineBackend(session_history=session_history or [])
    registry = ReadToolRegistry(backend, runner=InlineTimeoutRunner())
    # Deterministic refusal ids so denials/refusals are stable across runs.
    counter = {"n": 0}

    def _refusal_id(tool_name: str, source: str) -> str:
        counter["n"] += 1
        return f"refusal:{tool_name}:{counter['n']}"

    return ReadOnlyEnforcer(registry, refusal_record_id_factory=_refusal_id)


def _fixed_clock(value_ms: int):
    return lambda: value_ms


def _config(*, max_steps: int = 8, budget_seconds: int = 30) -> SidecarConfig:
    return SidecarConfig(probe_max_steps=max_steps, probe_budget_seconds=budget_seconds)


# --- step-cap termination (R8.2, R8.4, R8.8) ------------------------------


def test_probe_stops_at_step_cap_when_no_hypothesis_resolves():
    """The loop never exceeds the step cap even if nothing resolves (R8.4)."""

    # One unresolved hypothesis that the strategy never resolves; the loop must
    # stop after exactly `probe_max_steps` attempted calls.
    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "session record")])
    # A long script of in-set calls that resolve nothing.
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})]
        * 100
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),  # clock frozen: only the step cap can stop it
        config=_config(max_steps=8, budget_seconds=300),
    )

    assert isinstance(result, ProbeResult)
    assert result.stop_reason is ProbeStopReason.STEP_CAP_REACHED
    assert result.next == ROUTE_SYNTHESIZE_VERDICT
    assert state.probe_steps_used == 8
    # Each allowed call produced one bound evidence item.
    assert len(state.evidence) == 8


def test_probe_respects_step_cap_of_one():
    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})] * 5
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(max_steps=1, budget_seconds=300),
    )

    assert result.stop_reason is ProbeStopReason.STEP_CAP_REACHED
    assert state.probe_steps_used == 1


def test_failed_calls_count_against_the_step_cap():
    """A no-data call records a gap and still consumes a step (R8.8)."""

    state = _state([_hypothesis("h1")])
    # Empty session history -> tool returns a gap (no data), never resolving h1.
    enforcer = _enforcer(session_history=[])
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})] * 100
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(max_steps=5, budget_seconds=300),
    )

    assert result.stop_reason is ProbeStopReason.STEP_CAP_REACHED
    assert state.probe_steps_used == 5  # failed calls still counted
    assert len(state.gaps) == 5         # each no-data call recorded a gap
    assert state.evidence == []         # nothing bound


# --- wall-clock budget termination (R8.3, R8.5) ---------------------------


def test_probe_stops_when_budget_exhausted():
    """Elapsed >= budget stops the loop before the step cap (R8.5)."""

    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})] * 100
    )

    # Clock jumps to exactly the budget (30s = 30000ms) after the loop anchors
    # probe_started_ms at 0, so the first post-iteration check trips the budget.
    times = iter([0, 30_000, 30_000, 30_000])
    clock = lambda: next(times)

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=clock,
        config=_config(max_steps=50, budget_seconds=30),
    )

    assert result.stop_reason is ProbeStopReason.BUDGET_EXHAUSTED
    assert result.next == ROUTE_SYNTHESIZE_VERDICT
    # One call ran before the budget check tripped.
    assert state.probe_steps_used == 1


def test_probe_stops_immediately_when_budget_already_exhausted_on_entry():
    """If the probe was started earlier and the budget is already spent, no calls issue."""

    # probe_started_ms already set far in the past; on entry elapsed >= budget.
    state = _state([_hypothesis("h1")], started_ms=0)
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})] * 5
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(60_000),  # 60s elapsed, budget 30s
        config=_config(max_steps=50, budget_seconds=30),
    )

    assert result.stop_reason is ProbeStopReason.BUDGET_EXHAUSTED
    assert state.probe_steps_used == 0  # never issued a call


# --- all-resolved early termination (R8.1, R8.7) --------------------------


def test_probe_stops_when_all_hypotheses_resolved():
    """The loop stops as soon as no unresolved hypotheses remain (R8.7)."""

    state = _state([_hypothesis("h1"), _hypothesis("h2")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    # Two calls, each resolving one hypothesis.
    strategy = ScriptedProbeStrategy(
        [
            ProbeCall(
                tool_name="get_session_history",
                args={"alert_id": "alert-1"},
                resolve_hypotheses=("h1",),
            ),
            ProbeCall(
                tool_name="get_session_history",
                args={"alert_id": "alert-1"},
                resolve_hypotheses=("h2",),
            ),
            # A third call that should never run.
            ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"}),
        ]
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(max_steps=50, budget_seconds=300),
    )

    assert result.stop_reason is ProbeStopReason.ALL_RESOLVED
    assert result.next == ROUTE_SYNTHESIZE_VERDICT
    assert state.probe_steps_used == 2  # stopped before the third call
    assert all(h.resolved for h in state.hypotheses)


def test_probe_stops_immediately_with_no_hypotheses():
    """No hypotheses at all means nothing to probe; stop and synthesize."""

    state = _state([])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})]
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(),
    )

    assert result.stop_reason is ProbeStopReason.ALL_RESOLVED
    assert state.probe_steps_used == 0


def test_probe_stops_when_strategy_has_no_proposal():
    """A strategy that runs out of proposals stops the loop (defensive)."""

    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy([])  # nothing to propose

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(),
    )

    assert result.stop_reason is ProbeStopReason.NO_PROPOSAL
    assert result.next == ROUTE_SYNTHESIZE_VERDICT
    assert state.probe_steps_used == 0


# --- out-of-set rejection (R8.6, R8.9) ------------------------------------


def test_out_of_set_tool_request_is_denied_recorded_and_counts_a_step():
    """An out-of-set request is denied before execution and recorded (R8.9)."""

    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy(
        [
            # Not a real Read_Tool: must be denied before execution.
            ProbeCall(tool_name="delete_everything", args={"target": "prod"}),
        ]
        * 100
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(max_steps=3, budget_seconds=300),
    )

    assert result.stop_reason is ProbeStopReason.STEP_CAP_REACHED
    # Denied calls still consume steps (R8.8).
    assert state.probe_steps_used == 3
    # Each denial recorded.
    assert len(state.denied_invocations) == 3
    assert all(d.requestedTool == "delete_everything" for d in state.denied_invocations)
    assert all(d.source == "probe" for d in state.denied_invocations)
    # Refusal evidence recorded for each denial.
    refusals = [e for e in state.evidence if e.kind == "refusal"]
    assert len(refusals) == 3
    # Backend never executed anything (no bound context evidence).
    assert [e for e in state.evidence if e.kind == "context"] == []


def test_denied_request_can_bind_refusal_to_provided_record_id():
    state = _state([_hypothesis("h1")])
    enforcer = _enforcer()
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="write_config", audit_record_id="audit-42")]
    )

    probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(0),
        config=_config(max_steps=1, budget_seconds=300),
    )

    refusals = [e for e in state.evidence if e.kind == "refusal"]
    assert len(refusals) == 1
    assert refusals[0].auditRecordId == "audit-42"


# --- termination guarantee ------------------------------------------------


@pytest.mark.parametrize("max_steps", [1, 2, 8, 50])
def test_probe_always_terminates_and_routes_to_synthesis(max_steps):
    """For any step cap, with a frozen clock and never-resolving strategy, the
    loop terminates within the cap and routes to verdict synthesis."""

    state = _state([_hypothesis("h1"), _hypothesis("h2")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    # Infinite-feeling script (well beyond any cap) that resolves nothing.
    strategy = ScriptedProbeStrategy(
        [ProbeCall(tool_name="get_session_history", args={"alert_id": "alert-1"})]
        * (max_steps + 50)
    )

    result = probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(123),  # never advances
        config=_config(max_steps=max_steps, budget_seconds=300),
    )

    assert result.next == ROUTE_SYNTHESIZE_VERDICT
    assert state.probe_steps_used == max_steps
    assert result.stop_reason is ProbeStopReason.STEP_CAP_REACHED


def test_probe_anchors_started_ms_on_first_entry():
    state = _state([_hypothesis("h1")])
    enforcer = _enforcer(session_history=[EngineRecord("audit-1", "s")])
    strategy = ScriptedProbeStrategy([])

    assert state.probe_started_ms is None
    probe(
        state,
        enforcer,
        strategy=strategy,
        clock=_fixed_clock(777),
        config=_config(),
    )
    assert state.probe_started_ms == 777
