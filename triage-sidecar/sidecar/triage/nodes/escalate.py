"""The ``escalate`` Investigation_Graph node (task 16.1, R1.1/R9.6; hook for R11).

``escalate`` is the *fail-open-to-human* rendezvous. Every path that cannot (or
must not) produce an autonomous recommendation converges here:

  * an invalid alert envelope at intake (R4.2),
  * an ``uncertain`` / malformed / schema-invalid synthesized verdict (R9.4, R9.6),
  * the total-budget-overrun and unrecoverable-failure guards (R13.3, R13.4,
    wired in task 20), and
  * the ``draft_action`` defensive path when no confident verdict exists.

State effects (idempotent)
--------------------------
The node marks the run ``escalated`` (idempotently — repeated escalation is a
no-op on the flag) and **guarantees no autonomous** :class:`Recommended_Action`
**survives for a non-confident verdict** (R9.6): if the current verdict is
missing or ``uncertain``, any ``recommended_action`` is cleared. It performs
**zero** writes, blocks, or enforcement operations (R1.1) — it only mutates
in-memory investigation state and hands off to the HITL manager.

Routing
-------
The escalate node's single successor is ``emit_report`` (:data:`ROUTE_EMIT_REPORT`).
In the assembled graph the human-in-the-loop *pause* happens **between** escalate
and emit_report: the checkpoint/HITL manager persists the state, waits for a
durable-persistence confirmation, pauses until a Control_Tower decision, and
resumes (R11). That machinery is task 19; this node establishes the routing and
state effects now and exposes a **documented hook** the HITL manager plugs into.

The HITL hook (seam for task 19)
--------------------------------
:class:`EscalationHook` is the injectable seam. When provided, ``escalate`` calls
``hook.on_escalate(state)`` after marking the state, letting task 19's manager run
persist → pause → (later) resume without this node importing the HITL package.
The default is no hook (a pure state transition), so the node — and the nodes that
route into it — stay testable in isolation. The hook must not execute any
enforcement action; it only persists/pauses and coordinates the dual-control
approval that governs whether a human may act on a drafted advisory action.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Protocol, runtime_checkable

from sidecar.models import TriageState, VerdictValue

# --- Node identifier + routing literal (equal to the target node name) -----
ESCALATE_NODE = "escalate"
ROUTE_EMIT_REPORT = "emit_report"


@runtime_checkable
class EscalationHook(Protocol):
    """The seam the checkpoint/HITL manager (task 19) plugs into.

    ``escalate`` invokes :meth:`on_escalate` (when a hook is provided) right after
    it records the escalation on the state. The task-19 implementation will use
    this to persist the investigation state via LangGraph checkpointing, confirm
    durability, and pause the run until a Control_Tower decision is recorded
    (R11.1–R11.3). Implementations must perform **no** enforcement action.
    """

    def on_escalate(self, state: TriageState) -> None:  # pragma: no cover - protocol
        ...


@dataclass(frozen=True)
class EscalateResult:
    """The outcome of the ``escalate`` node.

    Attributes:
        state: The updated :class:`TriageState` with ``escalated == True`` and no
            autonomous action for a non-confident verdict (R9.6).
        next: The routing literal — always :data:`ROUTE_EMIT_REPORT`. In the
            assembled graph the HITL pause occurs before emit_report (R11).
    """

    state: TriageState
    next: str


def escalate(
    state: TriageState,
    *,
    hook: Optional[EscalationHook] = None,
) -> EscalateResult:
    """Escalate the investigation to a human, then route to ``emit_report``.

    Marks the run ``escalated`` (idempotent) and, for a missing/``uncertain``
    verdict, clears any ``recommended_action`` so no autonomous action survives
    (R9.6). Invokes the injectable HITL ``hook`` when supplied (the seam for the
    task-19 persist → pause → resume manager). Performs no writes, blocks, or
    enforcement (R1.1).

    Args:
        state: The investigation state; mutated in place.
        hook: Optional HITL hook (checkpoint/pause). Defaults to ``None`` (a pure
            state transition), keeping this node testable in isolation.

    Returns:
        An :class:`EscalateResult` whose ``next`` is :data:`ROUTE_EMIT_REPORT`.
    """

    # Idempotent: escalating an already-escalated run leaves the flag set.
    state.escalated = True

    # R9.6: an uncertain (or absent) verdict must not carry an autonomous
    # Recommended_Action. Drafting only happens on the confident branch, but
    # clear defensively so this invariant holds no matter how escalate is reached.
    verdict = state.verdict
    if verdict is None or verdict.value == VerdictValue.UNCERTAIN:
        if state.recommended_action is not None:
            state.recommended_action = None

    # Hand off to the checkpoint/HITL manager when one is wired (task 19). Until
    # then this is a no-op and the node is a pure state transition.
    if hook is not None:
        hook.on_escalate(state)

    return EscalateResult(state=state, next=ROUTE_EMIT_REPORT)


def route_after_escalate(state: TriageState) -> str:
    """Return the successor node — escalate always proceeds to ``emit_report``.

    The human-in-the-loop pause happens between escalate and emit_report in the
    assembled graph (task 19); the routing target itself is unconditional.
    """

    return ROUTE_EMIT_REPORT


__all__ = [
    "ESCALATE_NODE",
    "ROUTE_EMIT_REPORT",
    "EscalationHook",
    "EscalateResult",
    "escalate",
    "route_after_escalate",
]
