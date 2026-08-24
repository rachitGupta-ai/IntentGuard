"""The bounded ``probe`` node of the Investigation_Graph (task 13.1, R8).

After hypotheses are formed, the ``Probe_Loop`` issues *scoped* read-only tool
calls to resolve unresolved hypotheses — but only inside strict bounds so that
investigation cost and latency stay predictable (R8). This module implements
that loop as a single node function with a deterministic, injectable
*strategy* and *clock* so its termination guarantee is exercisable without any
real time or engine I/O.

The loop invariant (R8.1, R8.4, R8.5, R8.7)
-------------------------------------------
Each iteration continues **only while all three hold**:

  * at least one **unresolved hypothesis** remains (else stop — R8.7), AND
  * ``probe_steps_used`` is **below** the configured maximum step count
    (``probe_max_steps``, 1–50, default 8 — else stop, R8.4), AND
  * the **elapsed wall-clock** since the probe began is **below** the configured
    budget (``probe_budget_seconds``, 1–300s, default 30s — else stop, R8.5).

When the invariant breaks for any reason the loop stops and the node routes to
``synthesize_verdict`` (R8.4, R8.5, R8.7).

Per-iteration behavior
----------------------
Each iteration asks the strategy for the next scoped call and routes it through
the :class:`~sidecar.tools.ReadOnlyEnforcer` (``source="probe"``):

  * **Every attempted call counts against the step cap** — including calls that
    are denied (out-of-set) or that fail / return no data. Failed calls still
    consume a step (R8.8).
  * An **in-set** call executes read-only; its bound evidence is appended to
    ``state.evidence`` and any recorded :class:`~sidecar.models.Gap` (no data,
    error, timeout, unbindable shortfall) is appended to ``state.gaps`` (R8.8).
  * An **out-of-set** request is **denied before execution**: the
    :class:`~sidecar.models.DeniedInvocation` is appended to
    ``state.denied_invocations`` and the refusal ``Evidence_Item`` (when the
    enforcer produced one) is appended to ``state.evidence`` — no tool runs and
    protected state is untouched (R8.6, R8.9).
  * Hypotheses the strategy dictates as resolved are marked ``resolved`` on the
    state so the loop can terminate as they are cleared (R8.1, R8.7).

Termination guarantee
---------------------
Because **every** attempted call increments ``probe_steps_used`` by exactly one
and the loop stops once ``probe_steps_used`` reaches the (finite, ≤50) step cap,
the loop always terminates — even if the clock never advances, the strategy
keeps proposing calls, and no hypothesis is ever resolved. A strategy that runs
out of proposals (returns ``None``) also stops the loop. In all cases the node
proceeds to ``synthesize_verdict``.

Routing
-------
The node returns a :class:`ProbeResult` exposing the updated ``state`` and a
``next`` routing literal, which is always :data:`ROUTE_SYNTHESIZE_VERDICT`
(the probe loop's only successor per the design's state diagram). This mirrors
the constant-name + result-object convention used by the other graph nodes.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Mapping, Optional, Protocol, runtime_checkable

from sidecar.config import SidecarConfig, get_config
from sidecar.models import TriageState
from sidecar.tools import ReadOnlyEnforcer

# --- Node identifier + routing literal (equal to the target node name) ----
PROBE_NODE = "probe"
ROUTE_SYNTHESIZE_VERDICT = "synthesize_verdict"

# The investigation stage recorded on gaps produced during probing.
PROBE_STAGE = "probe"

# The source label recorded on every probe-issued tool request (R8.9, R12.5).
PROBE_SOURCE = "probe"

_MS_PER_SECOND = 1000


# A clock returns the current wall-clock time in **milliseconds**. Injectable so
# the probe wall-clock budget (R8.3/R8.5) is deterministic under test.
Clock = Callable[[], int]


def wall_clock_ms() -> int:
    """Default clock: current wall-clock time in epoch milliseconds."""

    return int(time.time() * _MS_PER_SECOND)


@dataclass(frozen=True)
class ProbeCall:
    """A single scoped Read_Tool call proposed by a :class:`ProbeStrategy`.

    Attributes:
        tool_name: The tool the strategy wants to call. May be an out-of-set
            name to exercise read-only rejection (R8.9); the enforcer decides.
        args: Keyword arguments forwarded to the tool when the call is allowed.
            Ignored on denial (nothing executes).
        resolve_hypotheses: Ids of hypotheses to mark ``resolved`` after this
            call is processed — how the strategy "dictates" resolution (R8.1,
            R8.7). Unknown ids are ignored.
        audit_record_id: Optional ``Audit_History`` record id to bind onto a
            refusal ``Evidence_Item`` when the request is out-of-set; when absent
            the enforcer synthesises a self-referential refusal id.
        source: Attribution label recorded on any denial/refusal; defaults to
            ``"probe"``.
    """

    tool_name: str
    args: Mapping[str, object] = field(default_factory=dict)
    resolve_hypotheses: tuple[str, ...] = ()
    audit_record_id: Optional[str] = None
    source: str = PROBE_SOURCE


@runtime_checkable
class ProbeStrategy(Protocol):
    """Proposes the next scoped :class:`ProbeCall` for the Probe_Loop.

    Given the current :class:`TriageState` (whose unresolved hypotheses drive
    probing), return the next scoped call, or ``None`` when the strategy has
    nothing further to propose (which stops the loop and proceeds to verdict
    synthesis). Implementations should be deterministic for testability.
    """

    def propose(self, state: TriageState) -> Optional[ProbeCall]:
        """Return the next scoped call, or ``None`` to stop probing."""
        ...


class ScriptedProbeStrategy:
    """A deterministic :class:`ProbeStrategy` that replays a fixed script.

    Yields the pre-supplied :class:`ProbeCall` s in order, one per ``propose``
    call, then returns ``None`` (nothing more to propose). Handy for tests and
    for wiring a predictable probe sequence: because the loop is bounded by the
    step cap regardless, an over-long script is simply truncated by the bounds.
    """

    def __init__(self, calls: "list[ProbeCall] | tuple[ProbeCall, ...]") -> None:
        self._calls = list(calls)
        self._index = 0

    def propose(self, state: TriageState) -> Optional[ProbeCall]:
        if self._index >= len(self._calls):
            return None
        call = self._calls[self._index]
        self._index += 1
        return call


class ProbeStopReason(str, Enum):
    """Why the Probe_Loop stopped (all reasons route to verdict synthesis)."""

    ALL_RESOLVED = "ALL_RESOLVED"          # no unresolved hypotheses remain (R8.7)
    STEP_CAP_REACHED = "STEP_CAP_REACHED"  # probe_steps_used == max (R8.4)
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"  # elapsed >= budget (R8.5)
    NO_PROPOSAL = "NO_PROPOSAL"            # strategy had nothing more to propose


@dataclass(frozen=True)
class ProbeResult:
    """The outcome of the ``probe`` node.

    Attributes:
        state: The updated :class:`TriageState` (evidence, gaps, denials, probe
            counters, and resolved-hypothesis flags applied in place).
        next: The routing literal for the next node — always
            :data:`ROUTE_SYNTHESIZE_VERDICT`.
        stop_reason: Why the loop terminated.
        steps_used: ``state.probe_steps_used`` at termination (convenience).
    """

    state: TriageState
    next: str
    stop_reason: ProbeStopReason
    steps_used: int


def _unresolved(state: TriageState) -> list:
    """The hypotheses that are not yet resolved."""

    return [h for h in state.hypotheses if not h.resolved]


def _elapsed_ms(state: TriageState, now_ms: int) -> int:
    """Milliseconds elapsed since the probe loop started."""

    if state.probe_started_ms is None:
        return 0
    return now_ms - state.probe_started_ms


def _next_stop_reason(
    state: TriageState,
    *,
    max_steps: int,
    budget_ms: int,
    now_ms: int,
) -> Optional[ProbeStopReason]:
    """Evaluate the loop invariant; return a stop reason, or ``None`` to continue.

    The checks are ordered so that a naturally-finished investigation
    (no unresolved hypotheses, R8.7) is reported before the bounds, and the step
    cap (R8.4) before the wall-clock budget (R8.5).
    """

    if not _unresolved(state):
        return ProbeStopReason.ALL_RESOLVED
    if state.probe_steps_used >= max_steps:
        return ProbeStopReason.STEP_CAP_REACHED
    if _elapsed_ms(state, now_ms) >= budget_ms:
        return ProbeStopReason.BUDGET_EXHAUSTED
    return None


def _apply_resolutions(state: TriageState, hypothesis_ids: tuple[str, ...]) -> None:
    """Mark the named hypotheses ``resolved`` as the strategy dictates (R8.1)."""

    if not hypothesis_ids:
        return
    targets = set(hypothesis_ids)
    for hypothesis in state.hypotheses:
        if hypothesis.id in targets and not hypothesis.resolved:
            hypothesis.resolved = True


def probe(
    state: TriageState,
    enforcer: ReadOnlyEnforcer,
    *,
    strategy: ProbeStrategy,
    clock: Clock = wall_clock_ms,
    config: Optional[SidecarConfig] = None,
) -> ProbeResult:
    """Run the bounded Probe_Loop, then route to verdict synthesis (R8).

    Issues scoped Read_Tool calls (via ``enforcer.guarded_invoke`` with
    ``source="probe"``) while unresolved hypotheses remain and neither the step
    cap nor the wall-clock budget is reached. Every attempted call — allowed,
    failed, or denied — counts against the step cap (R8.8). Bound evidence and
    gaps are folded into ``state``; out-of-set requests are denied before
    execution and recorded (R8.6, R8.9). The loop always terminates and proceeds
    to ``synthesize_verdict``.

    Args:
        state: The mutable investigation state; mutated in place.
        enforcer: The read-only enforcer wrapping the tool registry — the single
            choke point that allows in-set tools and denies out-of-set requests
            before execution.
        strategy: Injectable, deterministic proposer of the next scoped call.
        clock: Injectable clock returning epoch milliseconds (for the wall-clock
            budget); defaults to :func:`wall_clock_ms`.
        config: Optional sidecar configuration supplying ``probe_max_steps`` and
            ``probe_budget_seconds``; defaults to the process-wide configuration.

    Returns:
        A :class:`ProbeResult` whose ``next`` is :data:`ROUTE_SYNTHESIZE_VERDICT`.
    """

    cfg = config or get_config()
    max_steps = cfg.probe_max_steps
    budget_ms = cfg.probe_budget_seconds * _MS_PER_SECOND

    # Anchor the wall-clock budget at the first entry into the loop (R8.3). A
    # fresh anchor means zero elapsed; on re-entry we read the live clock so any
    # budget already spent is reflected before issuing a call (R8.5).
    if state.probe_started_ms is None:
        state.probe_started_ms = clock()
        now_ms = state.probe_started_ms
    else:
        now_ms = clock()

    stop_reason = _next_stop_reason(
        state, max_steps=max_steps, budget_ms=budget_ms, now_ms=now_ms
    )

    while stop_reason is None:
        call = strategy.propose(state)
        if call is None:
            stop_reason = ProbeStopReason.NO_PROPOSAL
            break

        # Route the proposed call through the read-only enforcer. In-set tools
        # execute read-only; out-of-set requests are denied before execution.
        guarded = enforcer.guarded_invoke(
            call.tool_name,
            source=call.source,
            args=call.args,
            audit_record_id=call.audit_record_id,
        )

        # Every attempted call — allowed, failed, or denied — costs one step
        # against the configured maximum (R8.8).
        state.probe_steps_used = state.probe_steps_used + 1

        if guarded.allowed:
            tool_result = guarded.tool_result
            if tool_result is not None:
                if tool_result.evidence:
                    state.evidence.extend(tool_result.evidence)
                if tool_result.gap is not None:
                    state.gaps.append(tool_result.gap)
        else:
            # Denied out-of-set request: record the denial (always) and the
            # refusal evidence (when produced); no tool ran (R8.6, R8.9).
            if guarded.denial is not None:
                state.denied_invocations.append(guarded.denial)
            if guarded.refusal_evidence is not None:
                state.evidence.append(guarded.refusal_evidence)

        # Mark hypotheses resolved as the strategy dictates (R8.1, R8.7).
        _apply_resolutions(state, call.resolve_hypotheses)

        # Re-evaluate the loop invariant with a fresh clock reading so the
        # wall-clock budget is honored between iterations (R8.5).
        stop_reason = _next_stop_reason(
            state, max_steps=max_steps, budget_ms=budget_ms, now_ms=clock()
        )

    return ProbeResult(
        state=state,
        next=ROUTE_SYNTHESIZE_VERDICT,
        stop_reason=stop_reason,
        steps_used=state.probe_steps_used,
    )


def route_after_probe(state: TriageState) -> str:
    """Return the successor node — the probe loop always proceeds to synthesis.

    The Probe_Loop records gaps/denials instead of halting, so it has no failure
    branch: the graph unconditionally advances to ``synthesize_verdict``
    (R8.4, R8.5, R8.7).
    """

    return ROUTE_SYNTHESIZE_VERDICT


__all__ = [
    "PROBE_NODE",
    "ROUTE_SYNTHESIZE_VERDICT",
    "PROBE_STAGE",
    "PROBE_SOURCE",
    "Clock",
    "wall_clock_ms",
    "ProbeCall",
    "ProbeStrategy",
    "ScriptedProbeStrategy",
    "ProbeStopReason",
    "ProbeResult",
    "probe",
    "route_after_probe",
]
