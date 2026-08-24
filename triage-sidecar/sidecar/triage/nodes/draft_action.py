"""The ``draft_action`` Investigation_Graph node (task 16.1, R1.1/R1.4/R1.5/R1.6).

``draft_action`` runs on a **confident** synthesized verdict (the branch the
design's ``synthesize_verdict --> draft_action`` edge takes for a non-uncertain,
well-formed verdict). Its whole job is to draft **exactly one**
:class:`~sidecar.models.Recommended_Action` and route to ``emit_report``.

The advisory invariant (R1.1, R1.4, R1.6)
-----------------------------------------
This node is the sharp end of the sidecar's *advisory, read-mostly* contract, so
it is deliberately inert with respect to the outside world:

  * It performs **zero** writes, blocks, or enforcement operations — it only
    constructs an in-memory :class:`Recommended_Action` and stores it on
    ``state.recommended_action``. It touches no engine API and no tool.
  * The drafted action is **never executed**. ``Recommended_Action.executed`` is
    ``Literal[False]`` at the type level, so the sidecar cannot even represent an
    executed action (R1.4, R1.6).
  * At most **one** action is drafted per run, and the node's single successor is
    ``emit_report`` — supporting the "exactly one report per run" invariant.

Protected-state actions route to the Control_Tower (R1.5)
---------------------------------------------------------
If the drafted action *would* change IntentGuard configuration, protected state,
or an enforcement outcome, it is marked ``targetsProtectedState=True``. That flag
is the signal that the action must be **routed to the Control_Tower for human
approval** and left **unexecuted**, with the targeted configuration / protected
state / enforcement outcome **unchanged** until (dual-control) approval is
recorded (R1.5, R1.6). The sidecar itself never applies it regardless of the
flag; the flag drives whether the downstream Control_Tower requires the
four-eyes approval gate before a human may act on the advice. The actual
persist → pause → dual-control → resume mechanics live in the HITL manager
(task 19); the escalate node exposes the seam for it.

Deterministic, injectable drafting
-----------------------------------
The *content* of an action ultimately derives from analysis, but to keep this
node deterministic and dependency-free we derive a plain-language description
and the ``targetsProtectedState`` flag from the verdict value and its
``Threat_Category`` via an injectable :class:`ActionDrafter`. :class:`DefaultActionDrafter`
is the built-in deterministic policy; tests (and later a richer analysis-backed
drafter) can inject an alternative. The drafter only *describes* an action — it
never executes anything.

Defensive routing (R9.6)
------------------------
``draft_action`` is only reached on a confident verdict. If it is nonetheless
invoked with a missing/uncertain/malformed-rejected verdict, it drafts **no**
autonomous action (R9.6), marks the run ``escalated``, and routes to
:data:`ROUTE_ESCALATE` so a human makes the call — never failing open to allow.

Routing convention
-------------------
Returns a :class:`DraftActionResult` exposing the updated ``state`` and a
``next`` routing literal (:data:`ROUTE_EMIT_REPORT` on the normal path,
:data:`ROUTE_ESCALATE` on the defensive path). :func:`route_after_draft_action`
re-derives the same decision from state for a LangGraph conditional edge.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Protocol, runtime_checkable

from sidecar.models import (
    Recommended_Action,
    ThreatCategory,
    TriageState,
    Verdict,
    VerdictValue,
)

# --- Node identifier + routing literals (equal to the target node names) ---
DRAFT_ACTION_NODE = "draft_action"
ROUTE_EMIT_REPORT = "emit_report"
ROUTE_ESCALATE = "escalate"


@dataclass(frozen=True)
class ActionDraft:
    """The plain-language content a :class:`ActionDrafter` proposes.

    Attributes:
        description: Human-readable description of the *advisory* action for a
            Control_Tower approver. Never an instruction the sidecar executes.
        targetsProtectedState: Whether the action would change IntentGuard
            configuration, protected state, or an enforcement outcome (R1.5). A
            ``True`` value routes the action to the Control_Tower for dual-control
            approval and leaves the targeted state unchanged until approval.
    """

    description: str
    targetsProtectedState: bool


@runtime_checkable
class ActionDrafter(Protocol):
    """Injectable policy that derives an :class:`ActionDraft` from state.

    Implementations must be **pure describers**: they may inspect the verdict and
    investigation state but must never perform any write, block, enforcement, or
    other side effect. Determinism is required for testability.
    """

    def draft(self, state: TriageState, verdict: Verdict) -> ActionDraft:  # pragma: no cover - protocol
        ...


class DefaultActionDrafter:
    """The built-in deterministic drafting policy.

    Derives a description and the ``targetsProtectedState`` flag purely from the
    confident verdict's value and its ``Threat_Category``:

      * ``confirmed_threat`` → a containment action that *targets protected state*
        (e.g. revoke the affected session / credential), so it is routed to the
        Control_Tower for dual-control approval and left unexecuted (R1.5).
      * ``benign`` / ``false_positive`` → a triage annotation (dismiss / mark)
        that changes **no** enforcement outcome or protected state, so
        ``targetsProtectedState`` is ``False``.

    The policy never widens or weakens enforcement on its own; it only *describes*
    advice for a human.
    """

    # Short, category-specific hint appended to a containment description so the
    # Control_Tower approver sees what the confirmed threat looks like.
    _CONTAINMENT_HINT = {
        ThreatCategory.PROMPT_INJECTION: (
            "isolate the affected session and revoke the injected agent's access"
        ),
        ThreatCategory.SESSION_HIJACK: (
            "revoke the compromised session and force re-authentication of the actor"
        ),
        ThreatCategory.OFF_INTENT_AGENT: (
            "suspend the off-intent agent and quarantine its pending commands"
        ),
        ThreatCategory.BENIGN_ANOMALY: (
            "review the anomaly and confirm no containment is required"
        ),
        ThreatCategory.FALSE_POSITIVE: (
            "confirm the alert is a false positive and clear it"
        ),
    }

    def draft(self, state: TriageState, verdict: Verdict) -> ActionDraft:
        alert_id = state.alertId
        category = verdict.threatCategory

        if verdict.value == VerdictValue.CONFIRMED_THREAT:
            hint = self._CONTAINMENT_HINT.get(category, "contain the confirmed threat")
            return ActionDraft(
                description=(
                    f"Advisory: for alert {alert_id}, {hint} "
                    f"(confirmed {category.value}). Requires Control_Tower "
                    "dual-control approval; the sidecar will not execute it."
                ),
                # Containment changes an enforcement outcome / protected state.
                targetsProtectedState=True,
            )

        if verdict.value == VerdictValue.BENIGN:
            return ActionDraft(
                description=(
                    f"Advisory: dismiss alert {alert_id} as a benign anomaly "
                    f"({category.value}); no enforcement change is required."
                ),
                targetsProtectedState=False,
            )

        if verdict.value == VerdictValue.FALSE_POSITIVE:
            return ActionDraft(
                description=(
                    f"Advisory: mark alert {alert_id} as a false positive "
                    f"({category.value}); no enforcement change is required."
                ),
                targetsProtectedState=False,
            )

        # Any other value is not a confident verdict; the node handles this on
        # the defensive path before ever calling the drafter, but describe it
        # conservatively (targets protected state -> human must decide) in case a
        # custom drafter is invoked directly.
        return ActionDraft(
            description=(
                f"Advisory: escalate alert {alert_id} for human review "
                f"({category.value}); the sidecar will not execute any action."
            ),
            targetsProtectedState=True,
        )


@dataclass(frozen=True)
class DraftActionResult:
    """The outcome of the ``draft_action`` node.

    Attributes:
        state: The updated :class:`TriageState`. On the normal path it carries
            the single drafted ``recommended_action`` (R1.4); on the defensive
            path it carries ``escalated == True`` and no autonomous action (R9.6).
        next: The routing literal — :data:`ROUTE_EMIT_REPORT` on the normal path,
            :data:`ROUTE_ESCALATE` on the defensive path.
        action: The drafted :class:`Recommended_Action`, or ``None`` on the
            defensive path.
    """

    state: TriageState
    next: str
    action: Optional[Recommended_Action] = None

    @property
    def drafted(self) -> bool:
        """True iff a Recommended_Action was drafted (normal path)."""

        return self.action is not None

    @property
    def routes_to_control_tower(self) -> bool:
        """True iff the drafted action targets protected state (R1.5).

        Such an action must be routed to the Control_Tower for dual-control
        approval and left unexecuted with the targeted state unchanged.
        """

        return self.action is not None and self.action.targetsProtectedState


def _is_confident(verdict: Optional[Verdict]) -> bool:
    """A verdict is confident iff it is present, well-formed, and not uncertain.

    ``malformedRejected`` verdicts and ``uncertain`` verdicts are *not* confident
    and must never yield an autonomous action (R9.4, R9.6).
    """

    return (
        verdict is not None
        and not verdict.malformedRejected
        and verdict.value != VerdictValue.UNCERTAIN
    )


def draft_action(
    state: TriageState,
    *,
    drafter: Optional[ActionDrafter] = None,
) -> DraftActionResult:
    """Draft exactly one unexecuted Recommended_Action for a confident verdict.

    Args:
        state: The investigation state carrying the synthesized ``verdict``;
            mutated in place with the drafted ``recommended_action``.
        drafter: Injectable, deterministic action-describing policy; defaults to
            :class:`DefaultActionDrafter`. The drafter only *describes* advice —
            it never executes anything.

    Returns:
        A :class:`DraftActionResult`. On a confident verdict ``next`` is
        :data:`ROUTE_EMIT_REPORT` and ``state.recommended_action`` holds the one
        drafted, unexecuted action (routed to the Control_Tower when it targets
        protected state, R1.5). On a missing/uncertain/malformed verdict no
        autonomous action is drafted (R9.6), the run is marked ``escalated``, and
        ``next`` is :data:`ROUTE_ESCALATE`.

    Note:
        This node performs no writes, blocks, or enforcement (R1.1) and never
        executes the action (R1.4, R1.6). ``Recommended_Action.executed`` is
        ``Literal[False]`` at the type level.
    """

    verdict = state.verdict

    # Defensive path: never draft an autonomous action for a non-confident
    # verdict; fail open to a human instead (R9.6).
    if not _is_confident(verdict):
        state.escalated = True
        return DraftActionResult(state=state, next=ROUTE_ESCALATE, action=None)

    policy = drafter or DefaultActionDrafter()
    proposal = policy.draft(state, verdict)  # type: ignore[arg-type]  # confident => not None

    # Construct the single, unexecuted action. ``executed`` is ``Literal[False]``
    # so it cannot be recorded as executed (R1.4, R1.6). When it targets
    # protected state the flag routes it to the Control_Tower for dual-control
    # approval; the sidecar leaves the targeted state unchanged either way (R1.5).
    action = Recommended_Action(
        description=proposal.description,
        targetsProtectedState=bool(proposal.targetsProtectedState),
        executed=False,
    )

    state.recommended_action = action

    return DraftActionResult(state=state, next=ROUTE_EMIT_REPORT, action=action)


def route_after_draft_action(state: TriageState) -> str:
    """Re-derive the routing decision from state (for a LangGraph edge).

    Routes to :data:`ROUTE_ESCALATE` iff the run was escalated (the defensive
    no-confident-verdict path), otherwise to :data:`ROUTE_EMIT_REPORT`. Mirrors
    the ``next`` value returned by :func:`draft_action`.
    """

    return ROUTE_ESCALATE if state.escalated else ROUTE_EMIT_REPORT


__all__ = [
    "DRAFT_ACTION_NODE",
    "ROUTE_EMIT_REPORT",
    "ROUTE_ESCALATE",
    "ActionDraft",
    "ActionDrafter",
    "DefaultActionDrafter",
    "DraftActionResult",
    "draft_action",
    "route_after_draft_action",
]
