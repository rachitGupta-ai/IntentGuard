"""Dual-control (four-eyes) approval/rejection for Recommended_Actions (R11.4, R11.5).

Task 19.3 owns the second safety gate on the escalate-to-human loop: a
``Recommended_Action`` may proceed **only** after two *distinct* Control_Tower
approver identities have approved it, and any recorded rejection blocks it.

The rules, mirroring the engine's existing ``DualControlService.confirm``
semantics (design "Dual-control (R11.4)" / "Rejection (R11.5)"):

* **Two distinct approvers (R11.4).** An action proceeds only once two
  *different* ``approverId``s have each submitted an ``APPROVE`` decision.
* **Same-identity duplicate refused (R11.4).** A second approval from the *same*
  identity as an existing approval is refused: it does not count toward the two
  required approvers and the gate stays ``PENDING``.
* **Rejection is dominant (R11.5).** A ``REJECT`` decision is recorded and the
  action does not proceed. A recorded rejection prevents the action from
  proceeding *regardless* of how many approvals exist — rejection wins.

The gate is transport-agnostic: it ingests :class:`DecisionMessage` values
(the same records the decision channel receives) and reports a
:class:`DualControlStatus`. It holds no checkpoint state — persistence and
resume stay with :class:`~sidecar.hitl.manager.HITLManager` (task 19.1) — so the
graph / task 20 wiring can consult the gate alongside the manager without
duplicating checkpoint logic.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from enum import Enum

from sidecar.contract.outbound import ControlTowerDecision, DecisionMessage


class DualControlStatus(str, Enum):
    """The dual-control state of a paused investigation's Recommended_Action."""

    #: Not enough distinct approvals yet; the action may not proceed.
    PENDING = "pending"
    #: Two distinct approvers have approved; the action may proceed (R11.4).
    APPROVED = "approved"
    #: A rejection has been recorded; the action must not proceed (R11.5).
    REJECTED = "rejected"


@dataclass(frozen=True)
class DualControlOutcome:
    """The result of ingesting one :class:`DecisionMessage` into the gate.

    :param alertId: the investigation the decision applies to.
    :param status: the gate's status *after* this decision.
    :param mayProceed: whether the ``Recommended_Action`` may now proceed
        (``True`` only in :attr:`DualControlStatus.APPROVED`).
    :param accepted: whether *this* submission was counted. ``False`` when a
        same-identity duplicate approval is refused (R11.4) or when an approval
        arrives after a rejection has already blocked the action.
    :param approvers: the distinct approver identities recorded so far, in
        arrival order.
    :param detail: a human-readable explanation, primarily for refusals.
    """

    alertId: str
    status: DualControlStatus
    mayProceed: bool
    accepted: bool
    approvers: tuple[str, ...] = ()
    detail: str | None = None


@dataclass
class _DualControlRecord:
    """Internal per-alertId dual-control bookkeeping."""

    approvers: list[str] = field(default_factory=list)
    rejected: bool = False
    rejected_by: str | None = None

    def status(self) -> DualControlStatus:
        if self.rejected:
            return DualControlStatus.REJECTED
        if len(self.approvers) >= 2:
            return DualControlStatus.APPROVED
        return DualControlStatus.PENDING


#: Number of distinct approver identities required before an action proceeds (R11.4).
REQUIRED_APPROVERS = 2


class DualControlGate:
    """Four-eyes gate: two distinct approvers to proceed; rejection blocks (R11.4, R11.5).

    Thread-safe. Feed it :class:`DecisionMessage` values via :meth:`submit`; query
    the current state via :meth:`status`, :meth:`may_proceed`, and
    :meth:`approvers`. State is tracked per ``alertId`` so one gate instance can
    serve many concurrent investigations.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._records: dict[str, _DualControlRecord] = {}

    # -- ingestion ---------------------------------------------------------

    def submit(self, decision: DecisionMessage) -> DualControlOutcome:
        """Ingest one Control_Tower decision and return the resulting gate state.

        Approvals accrue distinct approver identities; a same-identity duplicate
        approval is refused (``accepted=False``) and the gate stays ``PENDING``
        (R11.4). A rejection is recorded and dominates: the action may not
        proceed regardless of approvals, and later approvals are refused (R11.5).

        :param decision: the Control_Tower decision to ingest.
        :returns: a :class:`DualControlOutcome` describing the gate after this
            decision.
        """
        alert_id = decision.alertId
        with self._lock:
            record = self._records.setdefault(alert_id, _DualControlRecord())

            if decision.decision is ControlTowerDecision.REJECT:
                record.rejected = True
                if record.rejected_by is None:
                    record.rejected_by = decision.approverId
                return self._outcome(
                    alert_id,
                    record,
                    accepted=True,
                    detail=f"rejection recorded by {decision.approverId!r}; action will not proceed (R11.5)",
                )

            # APPROVE path.
            if record.rejected:
                # A recorded rejection blocks the action regardless of approvals (R11.5).
                return self._outcome(
                    alert_id,
                    record,
                    accepted=False,
                    detail="approval refused: a rejection has already blocked this action (R11.5)",
                )

            if decision.approverId in record.approvers:
                # Same identity cannot supply both approvals (R11.4).
                return self._outcome(
                    alert_id,
                    record,
                    accepted=False,
                    detail=(
                        f"approval refused: approver {decision.approverId!r} has already "
                        "approved; two DISTINCT approvers are required (R11.4)"
                    ),
                )

            record.approvers.append(decision.approverId)
            return self._outcome(alert_id, record, accepted=True)

    def _outcome(
        self,
        alert_id: str,
        record: _DualControlRecord,
        *,
        accepted: bool,
        detail: str | None = None,
    ) -> DualControlOutcome:
        status = record.status()
        return DualControlOutcome(
            alertId=alert_id,
            status=status,
            mayProceed=status is DualControlStatus.APPROVED,
            accepted=accepted,
            approvers=tuple(record.approvers),
            detail=detail,
        )

    # -- queries -----------------------------------------------------------

    def status(self, alert_id: str) -> DualControlStatus:
        """The current dual-control status for ``alert_id`` (``PENDING`` if unknown)."""
        with self._lock:
            record = self._records.get(alert_id)
            return record.status() if record is not None else DualControlStatus.PENDING

    def may_proceed(self, alert_id: str) -> bool:
        """True iff the ``Recommended_Action`` for ``alert_id`` may proceed (R11.4)."""
        return self.status(alert_id) is DualControlStatus.APPROVED

    def approvers(self, alert_id: str) -> tuple[str, ...]:
        """The distinct approver identities recorded for ``alert_id``, in order."""
        with self._lock:
            record = self._records.get(alert_id)
            return tuple(record.approvers) if record is not None else ()

    def is_rejected(self, alert_id: str) -> bool:
        """True iff a rejection has been recorded for ``alert_id`` (R11.5)."""
        return self.status(alert_id) is DualControlStatus.REJECTED


__all__ = [
    "DualControlStatus",
    "DualControlOutcome",
    "DualControlGate",
    "REQUIRED_APPROVERS",
]
