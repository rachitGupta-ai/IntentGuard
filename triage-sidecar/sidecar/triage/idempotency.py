"""In-memory, thread-safe idempotency store for triage runs (R3).

The sidecar keys every investigation by ``alertId`` (R3.1) and admits **at most
one active run** per ``alertId`` (R3.4). This module provides that guarantee as
a small, dependency-free, in-memory store:

  * ``admit(alertId, now=...)`` performs an **atomic compare-and-set** under a
    lock so that concurrent or simultaneous first-time triggers for the same
    ``alertId`` resolve to exactly one admitted run; every other caller receives
    the in-progress response (R3.4, R3.5).
  * A trigger for an ``alertId`` whose run is still active returns
    ``IN_PROGRESS`` and starts nothing (R3.3).
  * A trigger for an ``alertId`` whose run **completed within the retention
    window** returns the stored ``Triage_Report`` and starts nothing (R3.2); the
    same stored report is returned every time, which underpins report identity
    (R3.6).
  * A trigger for an ``alertId`` whose most recent completed run finished **more
    than the retention period ago** is treated as ``ADMITTED_NEW`` and starts a
    fresh run (R3.7).

The retention window comes from ``SidecarConfig.retention_period_seconds``
(default 24h, configurable 1-168h). "now" is injectable via a ``clock`` callable
returning epoch **seconds**, so retention-boundary behavior is deterministic
under test.

Public API
----------
- ``RunState``          : enum ``{IN_PROGRESS, COMPLETED}`` (R3 state tracking).
- ``AdmissionOutcome``  : enum ``{ADMITTED_NEW, IN_PROGRESS, COMPLETED}``.
- ``AdmissionResult``   : the outcome plus, for ``COMPLETED``, the stored report.
- ``RunRecord``         : a read-only view of a run's state / completion / report.
- ``IdempotencyStore``  : the store with ``admit``, ``mark_completed``, ``lookup``.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from enum import Enum
from typing import Callable, Optional

from sidecar.config import SidecarConfig, get_config
from sidecar.models import Triage_Report

# A clock returns the current time as epoch seconds (float). Injectable so
# retention-boundary tests can advance time deterministically.
Clock = Callable[[], float]


class RunState(str, Enum):
    """Lifecycle state of a per-``alertId`` investigation run (R3)."""

    IN_PROGRESS = "IN_PROGRESS"  # an active run holds this alertId (R3.3, R3.4)
    COMPLETED = "COMPLETED"      # a run finished; report is stored (R3.2)


class AdmissionOutcome(str, Enum):
    """The result of an admission attempt for an ``alertId`` (R3.2-R3.5, R3.7)."""

    ADMITTED_NEW = "ADMITTED_NEW"  # caller should start a run (new or post-retention)
    IN_PROGRESS = "IN_PROGRESS"    # an active run exists; return in-progress response
    COMPLETED = "COMPLETED"        # completed within retention; stored report returned


@dataclass(frozen=True)
class AdmissionResult:
    """Outcome of :meth:`IdempotencyStore.admit`.

    ``report`` is populated **only** when ``outcome is AdmissionOutcome.COMPLETED``
    (a run completed within the retention window, R3.2/R3.6); it is ``None`` for
    ``ADMITTED_NEW`` and ``IN_PROGRESS``.
    """

    outcome: AdmissionOutcome
    report: Optional[Triage_Report] = None

    @property
    def admitted(self) -> bool:
        """True iff the caller should start a new Investigation_Graph run."""
        return self.outcome is AdmissionOutcome.ADMITTED_NEW


@dataclass(frozen=True)
class RunRecord:
    """An immutable snapshot of a run's stored state.

    ``completed_at`` and ``report`` are set only once the run is
    ``COMPLETED``; they are ``None`` while the run is ``IN_PROGRESS``.
    """

    alertId: str
    state: RunState
    started_at: float
    completed_at: Optional[float] = None
    report: Optional[Triage_Report] = None


class IdempotencyStore:
    """Thread-safe, in-memory idempotency index keyed by ``alertId`` (R3).

    A single lock guards every mutation and the compare-and-set inside
    :meth:`admit`, so simultaneous first-time triggers for one ``alertId`` admit
    exactly one run (R3.4, R3.5).
    """

    def __init__(
        self,
        config: Optional[SidecarConfig] = None,
        clock: Optional[Clock] = None,
    ) -> None:
        """Create a store.

        :param config: settings supplying ``retention_period_seconds`` (R3.7).
            Defaults to the process-wide configuration.
        :param clock: callable returning epoch **seconds**; injectable so
            retention-boundary tests are deterministic. Defaults to ``time.time``.
        """
        self._config = config if config is not None else get_config()
        self._clock: Clock = clock if clock is not None else time.time
        self._lock = threading.Lock()
        self._runs: dict[str, RunRecord] = {}

    # -- internals ---------------------------------------------------------
    def _now(self, now: Optional[float]) -> float:
        return float(now) if now is not None else float(self._clock())

    def _within_retention(self, completed_at: float, now: float) -> bool:
        """True iff a run completed at ``completed_at`` is still within retention.

        R3.7: a run that finished *more than* the retention period ago starts a
        new investigation; the boundary itself (elapsed == retention) is treated
        as still within the window.
        """
        return (now - completed_at) <= self._config.retention_period_seconds

    # -- public API --------------------------------------------------------
    def admit(self, alert_id: str, now: Optional[float] = None) -> AdmissionResult:
        """Attempt to admit a run for ``alert_id`` (atomic compare-and-set).

        Returns one of:

        * ``ADMITTED_NEW``  - no active run and no in-retention completed run
          existed, so a fresh ``IN_PROGRESS`` record was created; the caller
          should start the Investigation_Graph (R3.4, R3.5, R3.7).
        * ``IN_PROGRESS``   - an active run already holds this ``alert_id``; no
          new run is started (R3.3, R3.5).
        * ``COMPLETED``     - a run completed within the retention window; the
          stored report is returned and no new run is started (R3.2, R3.6).

        :param alert_id: the non-empty triggering ``alertId`` (R3.1).
        :param now: optional injected epoch-seconds timestamp for determinism.
        :raises ValueError: if ``alert_id`` is empty.
        """
        if not alert_id:
            raise ValueError("alert_id must be a non-empty string")

        current = self._now(now)
        with self._lock:
            record = self._runs.get(alert_id)

            if record is None:
                # First-ever trigger for this alertId: admit it.
                self._runs[alert_id] = RunRecord(
                    alertId=alert_id,
                    state=RunState.IN_PROGRESS,
                    started_at=current,
                )
                return AdmissionResult(AdmissionOutcome.ADMITTED_NEW)

            if record.state is RunState.IN_PROGRESS:
                # An active run already exists (R3.3, R3.4, R3.5).
                return AdmissionResult(AdmissionOutcome.IN_PROGRESS)

            # record.state is COMPLETED.
            assert record.completed_at is not None
            if self._within_retention(record.completed_at, current):
                # Return the stored report; start nothing (R3.2, R3.6).
                return AdmissionResult(AdmissionOutcome.COMPLETED, record.report)

            # Completed run is older than the retention window: start fresh (R3.7).
            self._runs[alert_id] = RunRecord(
                alertId=alert_id,
                state=RunState.IN_PROGRESS,
                started_at=current,
            )
            return AdmissionResult(AdmissionOutcome.ADMITTED_NEW)

    def mark_completed(
        self,
        alert_id: str,
        report: Triage_Report,
        now: Optional[float] = None,
    ) -> RunRecord:
        """Mark ``alert_id``'s run complete, storing ``report`` and a timestamp.

        Transitions the run to ``COMPLETED`` with the completion time and the
        stored ``Triage_Report`` returned to later duplicate triggers within the
        retention window (R3.2, R3.6).

        :param alert_id: the run's ``alertId``.
        :param report: the ``Triage_Report`` to store.
        :param now: optional injected epoch-seconds completion timestamp.
        :raises ValueError: if ``alert_id`` is empty.
        :returns: the stored :class:`RunRecord`.
        """
        if not alert_id:
            raise ValueError("alert_id must be a non-empty string")

        current = self._now(now)
        with self._lock:
            existing = self._runs.get(alert_id)
            started_at = existing.started_at if existing is not None else current
            record = RunRecord(
                alertId=alert_id,
                state=RunState.COMPLETED,
                started_at=started_at,
                completed_at=current,
                report=report,
            )
            self._runs[alert_id] = record
            return record

    def lookup(self, alert_id: str) -> Optional[RunRecord]:
        """Return the current :class:`RunRecord` for ``alert_id``, or ``None``.

        The returned record is an immutable snapshot; it does not reflect later
        state changes.
        """
        with self._lock:
            return self._runs.get(alert_id)

    def state_of(self, alert_id: str) -> Optional[RunState]:
        """Return the :class:`RunState` for ``alert_id``, or ``None`` if unknown."""
        record = self.lookup(alert_id)
        return record.state if record is not None else None


__all__ = [
    "Clock",
    "RunState",
    "AdmissionOutcome",
    "AdmissionResult",
    "RunRecord",
    "IdempotencyStore",
]
