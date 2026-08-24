"""Checkpoint store abstraction for the human-in-the-loop pause/resume (R11).

R11.1 requires that, at an escalate-to-human step, the investigation state is
**persisted and confirmed durable** *before* the graph pauses, and R11.3/R11.8
require that on a Control_Tower decision the investigation resumes **only** when
the persisted checkpoint is retrievable *and* passes integrity validation.

The intended production mechanism is LangGraph's checkpointer. To keep this
logic unit-testable without a live LangGraph runtime, checkpointing is wrapped
behind the small injectable :class:`CheckpointStore` interface, with
:class:`InMemoryCheckpointStore` as the default. A LangGraph-backed adapter can
implement the same interface later; the HITL manager (``manager.py``) only ever
talks to the interface.

Integrity validation stores a content checksum alongside the persisted state.
On retrieval the checksum is recomputed over the stored payload and compared to
the recorded checksum; a mismatch means the checkpoint is corrupt and must not
be restored (R11.8). The in-memory default exposes a ``corrupt`` test hook that
tampers with the stored payload so the corrupt-checkpoint path is exercisable.

Public API
----------
- ``StateNotPersistedError``   : raised when persistence is not confirmed durable (R11.7).
- ``CheckpointNotRestoredError``: raised when a checkpoint cannot be restored (R11.8).
- ``DurabilityConfirmation``   : the durable-write confirmation returned by ``persist``.
- ``CheckpointStore``          : the injectable checkpoint interface (Protocol).
- ``InMemoryCheckpointStore``  : the default, dependency-free implementation.
"""

from __future__ import annotations

import hashlib
import threading
from dataclasses import dataclass
from typing import Optional, Protocol, runtime_checkable

from sidecar.models import TriageState


class StateNotPersistedError(RuntimeError):
    """Raised when checkpoint persistence is not confirmed durable (R11.7).

    When this is raised at the escalate-to-human step, the investigation neither
    pauses nor advances and the pre-escalation state is retained.
    """

    def __init__(self, alert_id: str, detail: Optional[str] = None) -> None:
        self.alert_id = alert_id
        self.detail = detail
        message = f"investigation state for alertId {alert_id!r} could not be persisted"
        if detail:
            message = f"{message}: {detail}"
        super().__init__(message)


class CheckpointNotRestoredError(RuntimeError):
    """Raised when a persisted checkpoint cannot be restored (R11.8).

    Raised when, on a Control_Tower decision, the checkpoint is unretrievable or
    fails integrity validation. The investigation stays paused and does not
    resume.
    """

    def __init__(self, alert_id: str, detail: Optional[str] = None) -> None:
        self.alert_id = alert_id
        self.detail = detail
        message = f"checkpoint for alertId {alert_id!r} could not be restored"
        if detail:
            message = f"{message}: {detail}"
        super().__init__(message)


@dataclass(frozen=True)
class DurabilityConfirmation:
    """The result of a persist attempt (R11.1).

    ``durable`` is ``True`` only when the store has confirmed the write is
    durable; the HITL manager pauses **only** on a durable confirmation. When
    ``durable`` is ``False`` the manager raises :class:`StateNotPersistedError`
    and retains the pre-escalation state (R11.7).
    """

    alertId: str
    durable: bool
    checksum: Optional[str] = None
    detail: Optional[str] = None


def compute_checksum(payload: str) -> str:
    """Return a stable content checksum over a serialized checkpoint payload."""
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


@runtime_checkable
class CheckpointStore(Protocol):
    """Injectable checkpoint interface wrapping LangGraph checkpointing (R11).

    Implementations must persist a :class:`TriageState`, confirm durability
    before returning success, and support retrieval plus integrity validation so
    the HITL manager can enforce persist-then-pause (R11.1) and
    resume-with-integrity (R11.3, R11.8).
    """

    def persist(self, state: TriageState) -> DurabilityConfirmation:
        """Persist ``state`` and return a durability confirmation (R11.1)."""
        ...

    def has_checkpoint(self, alert_id: str) -> bool:
        """True iff a checkpoint for ``alert_id`` is retrievable (R11.3, R11.8)."""
        ...

    def validate_integrity(self, alert_id: str) -> bool:
        """True iff the stored checkpoint for ``alert_id`` passes integrity checks."""
        ...

    def retrieve(self, alert_id: str) -> Optional[TriageState]:
        """Return the persisted :class:`TriageState`, or ``None`` if unavailable."""
        ...

    def release(self, alert_id: str) -> None:
        """Release the retained checkpoint for ``alert_id`` (R11.2)."""
        ...


@dataclass
class _Record:
    """A stored checkpoint: the serialized payload plus its recorded checksum."""

    payload: str
    checksum: str


class InMemoryCheckpointStore:
    """Default, thread-safe, in-memory :class:`CheckpointStore`.

    Serializes each :class:`TriageState` to JSON and stores it with a checksum.
    Integrity validation recomputes the checksum over the stored payload and
    compares it to the recorded checksum, so tampering (see :meth:`corrupt`) is
    detected on retrieval (R11.8).

    Two injectable failure modes make the R11.7/R11.8 paths testable without a
    live LangGraph runtime:

    * ``fail_persist=True`` forces :meth:`persist` to report a non-durable write
      so the manager raises :class:`StateNotPersistedError` (R11.7).
    * :meth:`corrupt` tampers with a stored payload so integrity validation
      fails and the manager raises :class:`CheckpointNotRestoredError` (R11.8).
    """

    def __init__(self, *, fail_persist: bool = False) -> None:
        self._fail_persist = fail_persist
        self._lock = threading.Lock()
        self._records: dict[str, _Record] = {}

    # -- failure-mode controls (test hooks) --------------------------------
    def set_fail_persist(self, fail: bool) -> None:
        """Toggle the persistence-failure mode (R11.7 path)."""
        self._fail_persist = fail

    def corrupt(self, alert_id: str) -> bool:
        """Tamper with a stored payload so integrity validation fails (R11.8).

        Returns ``True`` if a record existed and was corrupted, else ``False``.
        The recorded checksum is left intact, so a later
        :meth:`validate_integrity` recomputes a different checksum and fails.
        """
        with self._lock:
            record = self._records.get(alert_id)
            if record is None:
                return False
            record.payload = record.payload + "\u0000corrupted"
            return True

    def drop(self, alert_id: str) -> bool:
        """Make a checkpoint unretrievable (simulate a lost checkpoint, R11.8)."""
        with self._lock:
            return self._records.pop(alert_id, None) is not None

    # -- CheckpointStore interface -----------------------------------------
    def persist(self, state: TriageState) -> DurabilityConfirmation:
        """Persist ``state`` under its ``alertId`` and confirm durability (R11.1).

        In the forced-failure mode nothing is stored and a non-durable
        confirmation is returned so the caller can honor R11.7.
        """
        alert_id = state.alertId
        if self._fail_persist:
            return DurabilityConfirmation(
                alertId=alert_id,
                durable=False,
                detail="persistence-failure mode is active",
            )

        payload = state.model_dump_json()
        checksum = compute_checksum(payload)
        with self._lock:
            self._records[alert_id] = _Record(payload=payload, checksum=checksum)
        return DurabilityConfirmation(alertId=alert_id, durable=True, checksum=checksum)

    def has_checkpoint(self, alert_id: str) -> bool:
        with self._lock:
            return alert_id in self._records

    def validate_integrity(self, alert_id: str) -> bool:
        """True iff a record exists and its payload matches its recorded checksum."""
        with self._lock:
            record = self._records.get(alert_id)
            if record is None:
                return False
            return compute_checksum(record.payload) == record.checksum

    def retrieve(self, alert_id: str) -> Optional[TriageState]:
        """Return the persisted state, or ``None`` if absent or unparseable."""
        with self._lock:
            record = self._records.get(alert_id)
        if record is None:
            return None
        try:
            return TriageState.model_validate_json(record.payload)
        except Exception:  # noqa: BLE001 - corrupt payloads must not raise here
            return None

    def release(self, alert_id: str) -> None:
        """Drop the retained checkpoint once retention is no longer required (R11.2)."""
        with self._lock:
            self._records.pop(alert_id, None)

    @property
    def retained_alert_ids(self) -> frozenset[str]:
        """The set of alertIds whose checkpoints are currently retained."""
        with self._lock:
            return frozenset(self._records)


__all__ = [
    "StateNotPersistedError",
    "CheckpointNotRestoredError",
    "DurabilityConfirmation",
    "compute_checksum",
    "CheckpointStore",
    "InMemoryCheckpointStore",
]
