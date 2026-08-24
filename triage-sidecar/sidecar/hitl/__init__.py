"""Checkpoint / human-in-the-loop (HITL) manager for the Alert Triage Sidecar.

Implements persist-then-pause, resume-with-integrity, dual-control approval,
and escalation-delivery retry. Populated by task 19.

Public API (task 19.1 - persist-then-pause and resume-with-integrity, R11)
--------------------------------------------------------------------------
Checkpoint store (injectable LangGraph-checkpointing wrapper):
  * ``CheckpointStore``           - the injectable checkpoint interface (Protocol).
  * ``InMemoryCheckpointStore``   - the default, dependency-free implementation
    with ``fail_persist`` and ``corrupt``/``drop`` test hooks (R11.7, R11.8).
  * ``DurabilityConfirmation``    - the durable-write confirmation from ``persist`` (R11.1).
  * ``compute_checksum``          - the checkpoint integrity checksum helper.

HITL manager (persist-then-pause / resume-with-integrity):
  * ``HITLManager``               - ``escalate_and_pause(state)`` (R11.1, R11.7) and
    ``resume(alertId, decision)`` (R11.2, R11.3, R11.8).
  * ``PausedInvestigation``       - the handle returned once durably checkpointed + paused.

Custom exceptions:
  * ``StateNotPersistedError``    - persistence not confirmed durable (R11.7).
  * ``CheckpointNotRestoredError``- checkpoint unretrievable / fails integrity (R11.8).

Dual-control (task 19.3, R11.4, R11.5)
--------------------------------------
Four-eyes gate: a ``Recommended_Action`` proceeds only after two distinct
approvers approve; a same-identity duplicate approval is refused; a rejection is
recorded and blocks the action.
  * ``DualControlGate``   - ``submit(decision)`` -> ``DualControlOutcome``; plus
    ``status`` / ``may_proceed`` / ``approvers`` / ``is_rejected`` queries.
  * ``DualControlStatus`` - PENDING / APPROVED / REJECTED.
  * ``DualControlOutcome``- the per-submission result (status, mayProceed,
    accepted, approvers, detail).
  * ``REQUIRED_APPROVERS``- the number of distinct approvers required (2).
``HITLManager`` embeds a gate and exposes ``submit_decision`` / ``may_proceed`` /
``dual_control_status`` so a decision can be recorded and evaluated together.

Escalation-delivery retry (task 19.5, R11.6, R11.9)
---------------------------------------------------
On an undeliverable escalation the paused, checkpointed state is retained and
delivery is retried up to ``escalation_retry_max_attempts`` at
``escalation_retry_interval_seconds`` without ever completing or advancing the
investigation; on exhausting retries an undelivered-escalation alert is raised
and the investigation stays paused and checkpointed.
  * ``EscalationDelivery``         - injectable delivery transport (Protocol).
  * ``ScriptedEscalationDelivery`` - test double (fail N times then succeed / always fail).
  * ``EscalationRetryDriver``      - the retain-and-retry driver (injectable ``sleep``).
  * ``EscalationDeliveryResult``   - the delivery outcome (``delivered`` / ``attempts``).
  * ``UndeliveredEscalationError`` - the undelivered-escalation alert (R11.9).
``HITLManager`` exposes ``deliver_escalation`` to drive retries while keeping the
investigation paused and checkpointed.
"""

from sidecar.hitl.checkpoint import (
    CheckpointNotRestoredError,
    CheckpointStore,
    DurabilityConfirmation,
    InMemoryCheckpointStore,
    StateNotPersistedError,
    compute_checksum,
)
from sidecar.hitl.delivery import (
    EscalationDelivery,
    EscalationDeliveryResult,
    EscalationRetryDriver,
    ScriptedEscalationDelivery,
    UndeliveredEscalationError,
)
from sidecar.hitl.dualcontrol import (
    REQUIRED_APPROVERS,
    DualControlGate,
    DualControlOutcome,
    DualControlStatus,
)
from sidecar.hitl.manager import HITLManager, PausedInvestigation

__all__ = [
    # checkpoint store
    "CheckpointStore",
    "InMemoryCheckpointStore",
    "DurabilityConfirmation",
    "compute_checksum",
    # HITL manager
    "HITLManager",
    "PausedInvestigation",
    # dual-control (R11.4, R11.5)
    "DualControlGate",
    "DualControlOutcome",
    "DualControlStatus",
    "REQUIRED_APPROVERS",
    # escalation-delivery retry (R11.6, R11.9)
    "EscalationDelivery",
    "ScriptedEscalationDelivery",
    "EscalationRetryDriver",
    "EscalationDeliveryResult",
    "UndeliveredEscalationError",
    # exceptions
    "StateNotPersistedError",
    "CheckpointNotRestoredError",
]
