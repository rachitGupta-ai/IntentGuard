"""Checkpoint / human-in-the-loop manager: persist-then-pause and resume (R11).

This module implements the two safety-critical halves of the escalate-to-human
loop that task 19.1 owns:

* **Persist-then-pause (R11.1, R11.7).** :meth:`HITLManager.escalate_and_pause`
  persists the investigation state via the injected :class:`CheckpointStore`,
  waits for a **durability confirmation**, and only then marks the investigation
  paused so that no further graph step runs until a Control_Tower decision is
  recorded. If persistence is not confirmed durable the investigation neither
  pauses nor advances: a :class:`StateNotPersistedError` is raised and the
  pre-escalation state is retained.

* **Resume-with-integrity (R11.2, R11.3, R11.8).** While paused, the persisted
  state is retained until a decision is recorded (R11.2).
  :meth:`HITLManager.resume` resumes the investigation from the persisted
  checkpoint **only** when that checkpoint is retrievable *and* passes integrity
  validation; otherwise the investigation stays paused and a
  :class:`CheckpointNotRestoredError` is raised (R11.8).

Related, integrated here:

* **Dual-control (R11.4, R11.5)** — two distinct approvers — is task 19.3, owned
  by :class:`~sidecar.hitl.dualcontrol.DualControlGate`. This manager embeds a
  gate and exposes :meth:`HITLManager.submit_decision` /
  :meth:`HITLManager.may_proceed` so the graph / task 20 can record a decision
  (for retention) *and* evaluate whether a ``Recommended_Action`` may proceed in
  one place. The gate can also be used standalone.

* **Escalation-delivery retry (R11.6, R11.9)** — task 19.5, owned by
  :class:`~sidecar.hitl.delivery.EscalationRetryDriver`. This manager exposes
  :meth:`HITLManager.deliver_escalation`, which drives delivery retries while the
  investigation stays paused and checkpointed and never advances it; on
  exhausting retries the undelivered-escalation alert
  (:class:`~sidecar.hitl.delivery.UndeliveredEscalationError`) propagates and the
  investigation remains paused. The driver can also be used standalone.

The manager talks only to the :class:`CheckpointStore` interface, so the
in-memory default is used in tests and a LangGraph-backed store can be injected
in production without changing this logic.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from sidecar.config import SidecarConfig
from sidecar.contract.outbound import DecisionMessage
from sidecar.hitl.checkpoint import (
    CheckpointNotRestoredError,
    CheckpointStore,
    InMemoryCheckpointStore,
    StateNotPersistedError,
)
from sidecar.hitl.delivery import (
    EscalationDelivery,
    EscalationDeliveryResult,
    EscalationRetryDriver,
)
from sidecar.hitl.dualcontrol import DualControlGate, DualControlOutcome, DualControlStatus
from sidecar.models import TriageState


@dataclass(frozen=True)
class PausedInvestigation:
    """Handle returned once an investigation is durably checkpointed and paused.

    ``paused`` is always ``True`` here (the manager only returns this on a
    durable persist); ``checksum`` is the durable-write confirmation's checksum,
    useful for correlating the paused state with its checkpoint.
    """

    alertId: str
    paused: bool
    checksum: Optional[str] = None


@dataclass
class _PauseRecord:
    """Internal per-alertId pause bookkeeping."""

    paused: bool = True
    checksum: Optional[str] = None
    decisions: list[DecisionMessage] = field(default_factory=list)


class HITLManager:
    """Persist-then-pause / resume-with-integrity manager (R11.1-R11.3, R11.7, R11.8).

    :param store: the checkpoint store to persist/retrieve state through.
        Defaults to an :class:`InMemoryCheckpointStore`.
    """

    def __init__(
        self,
        store: Optional[CheckpointStore] = None,
        *,
        dual_control: Optional[DualControlGate] = None,
    ) -> None:
        self._store: CheckpointStore = store if store is not None else InMemoryCheckpointStore()
        self._lock = threading.Lock()
        self._paused: dict[str, _PauseRecord] = {}
        self._dual_control: DualControlGate = (
            dual_control if dual_control is not None else DualControlGate()
        )

    @property
    def store(self) -> CheckpointStore:
        """The underlying checkpoint store (in-memory default or injected)."""
        return self._store

    @property
    def dual_control(self) -> DualControlGate:
        """The four-eyes gate enforcing two distinct approvers / rejection (R11.4, R11.5)."""
        return self._dual_control

    # -- persist-then-pause (R11.1, R11.7) ---------------------------------
    def escalate_and_pause(self, state: TriageState) -> PausedInvestigation:
        """Persist ``state`` and pause only after a durable confirmation (R11.1).

        Persists the investigation state through the checkpoint store and waits
        for a durability confirmation. Only once durability is confirmed is the
        investigation marked paused, so no further graph step runs until a
        Control_Tower decision is recorded.

        If persistence is not confirmed durable (or the store errors), the
        investigation neither pauses nor advances: a
        :class:`StateNotPersistedError` is raised and the caller's pre-escalation
        state is retained unchanged (R11.7).

        :param state: the investigation state to checkpoint.
        :returns: a :class:`PausedInvestigation` handle on success.
        :raises StateNotPersistedError: if durability is not confirmed (R11.7).
        """
        alert_id = state.alertId

        try:
            confirmation = self._store.persist(state)
        except StateNotPersistedError:
            raise
        except Exception as exc:  # noqa: BLE001 - any store error is a persist failure
            # Persistence failed: do not pause or advance; retain pre-escalation state.
            raise StateNotPersistedError(alert_id, str(exc)) from exc

        if not confirmation.durable:
            # No durable confirmation: do not pause or advance (R11.7).
            raise StateNotPersistedError(alert_id, confirmation.detail)

        # Durable confirmation received: pause the investigation (R11.1).
        with self._lock:
            self._paused[alert_id] = _PauseRecord(
                paused=True,
                checksum=confirmation.checksum,
            )

        return PausedInvestigation(
            alertId=alert_id,
            paused=True,
            checksum=confirmation.checksum,
        )

    def is_paused(self, alert_id: str) -> bool:
        """True iff the investigation for ``alert_id`` is paused awaiting a decision."""
        with self._lock:
            record = self._paused.get(alert_id)
            return record is not None and record.paused

    def recorded_decisions(self, alert_id: str) -> list[DecisionMessage]:
        """The Control_Tower decisions recorded for ``alert_id`` so far (R11.2)."""
        with self._lock:
            record = self._paused.get(alert_id)
            return list(record.decisions) if record is not None else []

    # -- dual-control (R11.4, R11.5) ---------------------------------------
    def submit_decision(self, decision: DecisionMessage) -> DualControlOutcome:
        """Record a Control_Tower decision and evaluate dual-control in one step.

        The decision is retained for the paused investigation (R11.2, satisfying
        the same retention obligation as :meth:`resume`) and ingested into the
        four-eyes gate. A ``Recommended_Action`` may proceed only after two
        *distinct* approver identities approve (R11.4); a same-identity duplicate
        approval is refused; a rejection is recorded and blocks the action
        (R11.5).

        This does **not** resume the investigation — the caller (graph / task 20)
        resumes via :meth:`resume` when :attr:`DualControlOutcome.mayProceed` is
        ``True`` and the checkpoint validates.

        :param decision: the Control_Tower decision to record and evaluate.
        :returns: the :class:`DualControlOutcome` after ingesting the decision.
        """
        with self._lock:
            record = self._paused.get(decision.alertId)
            if record is not None:
                record.decisions.append(decision)
        return self._dual_control.submit(decision)

    def may_proceed(self, alert_id: str) -> bool:
        """True iff dual-control permits the Recommended_Action to proceed (R11.4)."""
        return self._dual_control.may_proceed(alert_id)

    def dual_control_status(self, alert_id: str) -> DualControlStatus:
        """The dual-control status for ``alert_id`` (PENDING / APPROVED / REJECTED)."""
        return self._dual_control.status(alert_id)

    # -- escalation-delivery retry (R11.6, R11.9) --------------------------
    def deliver_escalation(
        self,
        alert_id: str,
        payload: Any,
        delivery: EscalationDelivery,
        *,
        config: Optional[SidecarConfig] = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> EscalationDeliveryResult:
        """Deliver the escalation for a paused investigation, retrying to the max.

        The investigation must already be paused and checkpointed
        (:meth:`escalate_and_pause`). Delivery is attempted through ``delivery``
        up to ``escalation_retry_max_attempts`` at ``escalation_retry_interval_seconds``
        via the injectable ``sleep`` (R11.6). Throughout, the investigation stays
        paused and its checkpoint is retained — delivery never completes or
        advances it, and delivery success is *not* a human decision (the
        investigation stays paused awaiting the Control_Tower decision).

        On exhausting every attempt the undelivered-escalation alert
        (:class:`~sidecar.hitl.delivery.UndeliveredEscalationError`) propagates and
        the investigation remains paused and checkpointed (R11.9).

        :param alert_id: the paused investigation's ``alertId``.
        :param payload: the escalation payload delivered to the Control_Tower
            (e.g. the ``Triage_Report`` or an escalation notice).
        :param delivery: the delivery transport to attempt through.
        :param config: retry-bound configuration; defaults to the process config.
        :param sleep: the wait-between-attempts callable (injectable for tests).
        :returns: an :class:`EscalationDeliveryResult` on successful delivery.
        :raises ValueError: if the investigation is not currently paused/checkpointed.
        :raises UndeliveredEscalationError: if delivery fails on every attempt (R11.9).
        """
        if not self.is_paused(alert_id):
            raise ValueError(
                f"cannot deliver escalation for alertId {alert_id!r}: the "
                "investigation is not paused/checkpointed"
            )

        driver = EscalationRetryDriver(delivery, config=config, sleep=sleep)
        # The driver only reports the delivery outcome; it never touches pause
        # state, so the investigation remains paused and checkpointed whether
        # delivery succeeds, fails transiently, or exhausts its retries.
        return driver.deliver_with_retry(alert_id, payload)

    # -- resume-with-integrity (R11.2, R11.3, R11.8) -----------------------
    def resume(self, alert_id: str, decision: DecisionMessage) -> TriageState:
        """Resume from the persisted checkpoint on a decision, with integrity (R11.3).

        Records the Control_Tower ``decision`` (R11.2 retention obligation ends
        once a decision is recorded) and resumes the investigation **only** when
        the persisted checkpoint is retrievable *and* passes integrity
        validation. Otherwise the investigation stays paused and a
        :class:`CheckpointNotRestoredError` is raised (R11.8).

        :param alert_id: the paused investigation's ``alertId``.
        :param decision: the Control_Tower decision that triggered the resume.
        :returns: the restored :class:`TriageState` to resume from (R11.3).
        :raises CheckpointNotRestoredError: if the checkpoint is unretrievable or
            fails integrity validation; the investigation stays paused (R11.8).
        """
        with self._lock:
            record = self._paused.get(alert_id)
            if record is not None:
                # Record the decision; retention obligation (R11.2) is satisfied.
                record.decisions.append(decision)

        # Resume only if the checkpoint is retrievable AND integrity-valid (R11.3).
        if not self._store.has_checkpoint(alert_id):
            raise CheckpointNotRestoredError(alert_id, "checkpoint is not retrievable")

        if not self._store.validate_integrity(alert_id):
            raise CheckpointNotRestoredError(alert_id, "checkpoint failed integrity validation")

        restored = self._store.retrieve(alert_id)
        if restored is None:
            raise CheckpointNotRestoredError(alert_id, "checkpoint could not be read")

        # Successful resume: clear the paused flag.
        with self._lock:
            record = self._paused.get(alert_id)
            if record is not None:
                record.paused = False

        return restored


__all__ = [
    "PausedInvestigation",
    "HITLManager",
]
