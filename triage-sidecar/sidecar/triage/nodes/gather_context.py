"""The ``gather_context`` Investigation_Graph node (task 10.1, R5).

After envelope validation succeeds (R4.4), the investigation gathers the two
pieces of background it needs to assess the alert:

  * the **session history** for the alert (``get_session_history``, R5.1), and
  * the actor's **Behavioral_Profile** (``get_actor_profile``, R5.2).

Both calls go through the read-only :class:`ReadToolRegistry`, which already:

  * runs each call under the per-tool 30s wall-clock timeout (R5.1, R5.2),
  * tags every returned element with its ``Audit_History`` record id and
    classifies the content as ``Untrusted_Content`` (R5.4, R12.1),
  * excludes any element that cannot be bound to a record id (R5.5), and
  * converts no-data / error / timeout into a recorded :class:`Gap` rather than
    raising (R5.3).

This node's job is therefore narrow and defensive: invoke the two tools, append
whatever bound context they returned to ``state.context``, append any recorded
gap to ``state.gaps``, and continue — preserving context already gathered even
when the other call fails (R5.3). For engine-wide triggers (e.g. a monitoring
gap) the envelope may carry no ``actorId``; rather than crash, the node records
a gap for the actor profile and moves on. On completion it routes to
``correlate``.

Routing
-------
Returns :data:`ROUTE_CORRELATE`. See :mod:`sidecar.triage.nodes` for the node
routing convention.
"""

from __future__ import annotations

from sidecar.models import Gap, TriageState
from sidecar.tools import ReadToolRegistry, ToolResult

# This node's identifier and the node it routes to on completion (R5 -> R6).
NODE_GATHER_CONTEXT = "gather_context"
ROUTE_CORRELATE = "correlate"

# Gap vocabulary for this stage (aligned with the tool layer's "context" stage).
_STAGE_CONTEXT = "context"

# Reason recorded when the envelope carries no actorId (engine-wide triggers
# such as a MonitoringGapWatchdog gap): the actor profile cannot be gathered,
# but the investigation must not crash (R5.3).
MISSING_ACTOR_ID_REASON = (
    "actorId absent on envelope; cannot gather actor Behavioral_Profile"
)


def _resolve_alert_id(state: TriageState) -> str:
    """The alertId to key session history on (prefer the validated envelope)."""

    if state.envelope is not None:
        return state.envelope.alertId
    return state.alertId


def _resolve_actor_id(state: TriageState) -> str | None:
    """The actorId for the profile lookup, or ``None`` for engine-wide triggers."""

    if state.envelope is not None:
        return state.envelope.actorId
    return None


def _absorb(state: TriageState, result: ToolResult) -> None:
    """Fold a :class:`ToolResult` into the state.

    Any bound evidence is appended to ``state.context`` (already record-id
    tagged and Untrusted-classified by the tool layer, R5.4/R12.1); any recorded
    gap — no-data, error, timeout, or an unbindable-record shortfall — is
    appended to ``state.gaps`` (R5.3, R5.5). Both may be present at once (a
    partial result): the gathered context is preserved and the shortfall
    recorded.
    """

    if result.evidence:
        state.context.extend(result.evidence)
    if result.gap is not None:
        state.gaps.append(result.gap)


def gather_context(state: TriageState, registry: ReadToolRegistry) -> str:
    """Gather session history and actor profile into ``state`` (R5).

    Invokes ``get_session_history`` and ``get_actor_profile`` (each already
    bounded by the per-tool timeout). Bound, record-id-tagged context is
    appended to ``state.context``; every no-data / error / timeout / unbindable
    shortfall is appended to ``state.gaps`` while context already gathered is
    preserved and the investigation continues (R5.1-R5.5). When the envelope has
    no ``actorId`` (engine-wide triggers) a gap is recorded for the actor
    profile instead of invoking the tool.

    Args:
        state: The mutable investigation state; mutated in place.
        registry: The injected read-only tool registry (a
            :class:`~sidecar.tools.StaticEngineBackend`-backed registry in
            tests).

    Returns:
        :data:`ROUTE_CORRELATE`, the next node to execute.
    """

    # Session history, keyed by the alert's alertId (R5.1). Preserve any context
    # this yields regardless of what the actor-profile call does next (R5.3).
    session_result = registry.get_session_history(_resolve_alert_id(state))
    _absorb(state, session_result)

    # Actor Behavioral_Profile, keyed by actorId (R5.2). Engine-wide triggers may
    # carry no actorId; record a gap rather than crashing (R5.3).
    actor_id = _resolve_actor_id(state)
    if actor_id is None:
        state.gaps.append(
            Gap(
                stage=_STAGE_CONTEXT,
                element="get_actor_profile",
                reason=MISSING_ACTOR_ID_REASON,
            )
        )
    else:
        actor_result = registry.get_actor_profile(actor_id)
        _absorb(state, actor_result)

    return ROUTE_CORRELATE


__all__ = [
    "gather_context",
    "NODE_GATHER_CONTEXT",
    "ROUTE_CORRELATE",
    "MISSING_ACTOR_ID_REASON",
]
