"""Injectable read-only engine backend for the Read_Tool layer.

Each of the five Read_Tools wraps an underlying engine data source. Those data
sources are reached through the :class:`ReadOnlyEngineBackend` protocol so the
concrete engine I/O (which, in production, runs under the read-only
``Service_Account`` — task 3.3) is swapped for deterministic doubles in tests.

The backend surface is intentionally *read-only*: every method fetches and
returns records; none writes, blocks, or enforces. Every returned
:class:`EngineRecord` carries the ``auditRecordId`` it was derived from so the
tool layer can tag derived evidence with its Audit_History record id
(R5.4, R6.4, R7.7, R10.3).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, Sequence, runtime_checkable


@dataclass(frozen=True)
class EngineRecord:
    """A single record returned by a read-only engine data source.

    ``auditRecordId`` is the Audit_History record id the datum derives from; the
    tool layer binds it onto the produced ``Evidence_Item``. A record whose
    ``auditRecordId`` is empty cannot be bound and is surfaced as a gap
    (R5.5, R10.4).
    """

    auditRecordId: str
    summary: str
    payload: dict = field(default_factory=dict)


@runtime_checkable
class ReadOnlyEngineBackend(Protocol):
    """Read-only data sources the five Read_Tools wrap.

    Implementations perform only reads. Any method may raise to signal an
    engine error (converted by the tool layer into a recorded gap) or return an
    empty sequence to signal no-data (also a recorded gap).
    """

    def get_session_history(self, alert_id: str) -> Sequence[EngineRecord]:
        """Session history for the alert's session (R5.1)."""
        ...

    def get_actor_profile(self, actor_id: str) -> Sequence[EngineRecord]:
        """The actor's Behavioral_Profile (R5.2)."""
        ...

    def query_audit_history(
        self, actor_id: str, from_ms: int, to_ms: int
    ) -> Sequence[EngineRecord]:
        """Audit history for ``actor_id`` in ``[from_ms, to_ms]`` (R6.3).

        Wraps ``AuditHistoryRepository.queryByUserAndTimeRange``. The tool layer
        clamps the window to <=30 days ending at the alert timestamp before
        calling this method.
        """
        ...

    def get_related_alerts(
        self, actor_id: str, session_id: str
    ) -> Sequence[EngineRecord]:
        """Related alerts for the actor/session (R6.1)."""
        ...

    def get_exfil_correlations(
        self, actor_id: str, session_id: str
    ) -> Sequence[EngineRecord]:
        """Secret-access-plus-egress exfiltration correlations (R6.2)."""
        ...


class StaticEngineBackend:
    """A deterministic in-memory :class:`ReadOnlyEngineBackend` double.

    Returns pre-seeded record lists per data source and records the arguments it
    was called with (handy for asserting actor-scoping and window bounds).
    Downstream tasks (10.1, 11.1, 13.1) and property tests can reuse it instead
    of standing up real engine I/O.
    """

    def __init__(
        self,
        *,
        session_history: Sequence[EngineRecord] | None = None,
        actor_profile: Sequence[EngineRecord] | None = None,
        audit_history: Sequence[EngineRecord] | None = None,
        related_alerts: Sequence[EngineRecord] | None = None,
        exfil_correlations: Sequence[EngineRecord] | None = None,
    ) -> None:
        self._session_history = list(session_history or [])
        self._actor_profile = list(actor_profile or [])
        self._audit_history = list(audit_history or [])
        self._related_alerts = list(related_alerts or [])
        self._exfil_correlations = list(exfil_correlations or [])
        # Call log: list of (method_name, kwargs) recorded in call order.
        self.calls: list[tuple[str, dict]] = []

    def get_session_history(self, alert_id: str) -> Sequence[EngineRecord]:
        self.calls.append(("get_session_history", {"alert_id": alert_id}))
        return list(self._session_history)

    def get_actor_profile(self, actor_id: str) -> Sequence[EngineRecord]:
        self.calls.append(("get_actor_profile", {"actor_id": actor_id}))
        return list(self._actor_profile)

    def query_audit_history(
        self, actor_id: str, from_ms: int, to_ms: int
    ) -> Sequence[EngineRecord]:
        self.calls.append(
            (
                "query_audit_history",
                {"actor_id": actor_id, "from_ms": from_ms, "to_ms": to_ms},
            )
        )
        return list(self._audit_history)

    def get_related_alerts(
        self, actor_id: str, session_id: str
    ) -> Sequence[EngineRecord]:
        self.calls.append(
            ("get_related_alerts", {"actor_id": actor_id, "session_id": session_id})
        )
        return list(self._related_alerts)

    def get_exfil_correlations(
        self, actor_id: str, session_id: str
    ) -> Sequence[EngineRecord]:
        self.calls.append(
            ("get_exfil_correlations", {"actor_id": actor_id, "session_id": session_id})
        )
        return list(self._exfil_correlations)


__all__ = ["EngineRecord", "ReadOnlyEngineBackend", "StaticEngineBackend"]
