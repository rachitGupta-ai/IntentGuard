"""The ``correlate`` node of the Investigation_Graph (task 11.1).

After context gathering completes, the investigation correlates the alert with
other signals to make coordinated or multi-step attacks visible (R6):

  * ``get_related_alerts(actor_id, session_id)`` — related alerts for the actor
    and session, capped at <=100 by the tool layer (R6.1).
  * ``get_exfil_correlations(actor_id, session_id)`` — secret-access-plus-egress
    exfiltration correlations, capped at <=100 by the tool layer (R6.2).
  * ``query_audit_history(actor_id, alert_timestamp_ms)`` — actor-scoped audit
    history bounded to a window not exceeding 30 days ending at the alert
    timestamp, used to bound correlation by actor and time range (R6.3). The
    window clamp is enforced by the tool layer.

Every correlated signal returned by a tool is already tagged with the
``Audit_History`` record id it derives from and classified as
``Untrusted_Content`` (R6.4, R12.1). A tool that returns no data, errors, or
times out yields a recorded :class:`~sidecar.models.Gap` rather than raising, so
the investigation always continues (R6.5). If the alert envelope lacks the
``actorId`` / ``sessionId`` a correlation needs, that shortfall is itself
recorded as a gap and the un-runnable tool is skipped.

Routing
-------
``correlate`` runs unconditionally after ``gather_context`` and always proceeds
to ``form_hypotheses`` (see the design's state diagram). :data:`CORRELATE_NODE`
and :data:`FORM_HYPOTHESES_NODE` name the nodes and :func:`route_after_correlate`
returns the (always ``form_hypotheses``) successor, matching the constant-name +
router convention used across the graph nodes.
"""

from __future__ import annotations

from typing import Optional

from sidecar.models import Gap, TriageState
from sidecar.tools import ReadToolRegistry, ToolResult

# Node identifiers (constant-name + router convention shared across nodes).
CORRELATE_NODE = "correlate"
FORM_HYPOTHESES_NODE = "form_hypotheses"

# Stage label recorded on any gap this node produces (aligned with the tool
# layer's correlate-stage gaps and the Gap.stage vocabulary).
_STAGE = "correlate"


def correlate(state: TriageState, registry: ReadToolRegistry) -> TriageState:
    """Correlate related alerts and exfiltration signals for the alert (R6).

    Invokes ``get_related_alerts`` and ``get_exfil_correlations`` (each capped at
    <=100 by the tool layer) and, where the actor is known, ``query_audit_history``
    scoped to that actor and a <=30-day window ending at the alert timestamp.
    Each bound correlated signal is appended to ``state.correlations`` and any
    shortfall (no data, error, timeout, or a missing ``actorId``/``sessionId``)
    is appended to ``state.gaps``; the investigation continues regardless (R6.5).

    Args:
        state: The investigation state; ``state.envelope`` supplies the actor,
            session, and alert timestamp when present.
        registry: The read-only tool registry (the structural read-only
            guarantee; caps and window bounds are enforced inside it).

    Returns:
        The same ``state`` instance with ``correlations`` and ``gaps`` extended.
    """

    envelope = state.envelope
    actor_id: Optional[str] = envelope.actorId if envelope is not None else None
    session_id: Optional[str] = envelope.sessionId if envelope is not None else None
    alert_ts: Optional[int] = (
        envelope.alertTimestampMs if envelope is not None else None
    )

    new_correlations = []
    new_gaps = []

    # --- related alerts + exfiltration correlations (R6.1, R6.2) ----------
    # Both are keyed by actor + session; without them the tool cannot be scoped.
    if actor_id and session_id:
        for result in (
            registry.get_related_alerts(actor_id, session_id),
            registry.get_exfil_correlations(actor_id, session_id),
        ):
            _collect(result, new_correlations, new_gaps)
    else:
        missing = _missing_fields(actorId=actor_id, sessionId=session_id)
        reason = (
            "actorId and sessionId are required to correlate; "
            f"missing: {missing}"
        )
        new_gaps.append(
            Gap(stage=_STAGE, element="get_related_alerts", reason=reason)
        )
        new_gaps.append(
            Gap(stage=_STAGE, element="get_exfil_correlations", reason=reason)
        )

    # --- actor/time-bounded audit history (R6.3) --------------------------
    # Needed to bound correlation by actor and time range; requires the actor
    # and the alert timestamp. The <=30-day window clamp lives in the tool layer.
    if actor_id and alert_ts is not None:
        _collect(
            registry.query_audit_history(actor_id, alert_ts),
            new_correlations,
            new_gaps,
        )
    else:
        missing = _missing_fields(actorId=actor_id, alertTimestampMs=alert_ts)
        new_gaps.append(
            Gap(
                stage=_STAGE,
                element="query_audit_history",
                reason=(
                    "actorId and alertTimestampMs are required to bound "
                    f"correlation by actor and time range; missing: {missing}"
                ),
            )
        )

    # Reassign (rather than mutate in place) so validate_assignment re-checks
    # the collections and each state instance keeps its own lists.
    if new_correlations:
        state.correlations = [*state.correlations, *new_correlations]
    if new_gaps:
        state.gaps = [*state.gaps, *new_gaps]

    return state


def route_after_correlate(state: TriageState) -> str:
    """Return the successor node — correlation always proceeds to hypotheses.

    ``correlate`` records gaps instead of halting, so there is no failure branch:
    the graph unconditionally advances to ``form_hypotheses`` (R6.5, R7).
    """

    return FORM_HYPOTHESES_NODE


def _collect(
    result: ToolResult,
    correlations: list,
    gaps: list,
) -> None:
    """Fold a :class:`ToolResult` into the pending correlation/gap lists.

    Evidence and gap may both be present (a partial result): keep every bound
    signal and still record the recorded shortfall.
    """

    correlations.extend(result.evidence)
    if result.gap is not None:
        gaps.append(result.gap)


def _missing_fields(**fields: object) -> str:
    """Name the ``None``/empty envelope fields for a gap reason string."""

    missing = [name for name, value in fields.items() if not value]
    return ", ".join(missing) if missing else "none"


__all__ = [
    "CORRELATE_NODE",
    "FORM_HYPOTHESES_NODE",
    "correlate",
    "route_after_correlate",
]
