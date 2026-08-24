"""Outbound Integration_Contract: stamping, report emission, decision channel.

This module implements the *sidecar → engine/Control_Tower* side of the
versioned contract (task 3.3). Three concerns live here:

1. **Message stamping (R14.1).** Every outgoing message — reports, decisions,
   and engine-directed tool calls — carries the Integration_Contract
   ``schemaVersion`` identifier. :class:`MessageStamper` stamps a
   ``Triage_Report`` (or any mapping payload) with the configured version and,
   for engine-directed requests, produces headers that also attach the read-only
   ``Service_Account`` credential (R14.2).

2. **Outbound report emission channel (R10.1, R14.1).** :class:`ReportEmitter`
   is the interface for sending a stamped ``Triage_Report`` to the Control_Tower.
   :class:`StampingReportEmitter` is the default implementation; the actual send
   transport is a pluggable :class:`ReportTransport` injection point (a real HTTP
   client is wired in at task 20.3). :class:`InMemoryReportTransport` is the
   testable default.

3. **Inbound Control_Tower decision channel wiring point (R11).** The engine
   posts approve/reject decisions to
   ``POST /triage/v1/investigations/{alertId}/decision``. :class:`DecisionMessage`
   is the structured decision (``approverId``, ``decision``, ``stepUpAuthenticated``);
   :class:`DecisionHandler` is the wiring-point interface the HITL resume manager
   implements at task 19. :class:`InMemoryDecisionChannel` records decisions until
   then, and :func:`parse_decision` validates a decoded decision body.

Everything is decoupled and exercisable with in-memory doubles; no transport or
HITL logic is baked in here.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping, Optional, Protocol, runtime_checkable

from sidecar.config import SidecarConfig, get_config
from sidecar.contract.auth import ServiceAccountCredential, default_service_account
from sidecar.models import Triage_Report

# Header carrying the schema-version identifier on engine-directed requests
# (the report/decision *bodies* carry it as a field; requests also carry it as a
# header so a transport can route/validate without decoding the body).
SCHEMA_VERSION_HEADER = "X-Triage-Schema-Version"


# ---------------------------------------------------------------------------
# Message stamping (R14.1, R14.2)
# ---------------------------------------------------------------------------


class MessageStamper:
    """Stamps outgoing messages with the schema version and attaches auth.

    The stamped ``schemaVersion`` is the sidecar's *current outgoing* version:
    an explicitly supplied ``schema_version`` (which must be in the supported
    set) or, by default, the highest supported version in the configuration.

    ``credential`` is the read-only ``Service_Account`` attached to every
    engine-directed request (R14.2); it defaults to a least-privileged
    credential so the stamper is usable on its own.
    """

    def __init__(
        self,
        config: Optional[SidecarConfig] = None,
        *,
        schema_version: Optional[str] = None,
        credential: Optional[ServiceAccountCredential] = None,
    ) -> None:
        self._config = config or get_config()
        self._credential = credential or default_service_account()
        self._schema_version = self._resolve_version(schema_version)

    def _resolve_version(self, requested: Optional[str]) -> str:
        supported = self._config.supported_schema_versions
        if requested is not None:
            if requested not in supported:
                raise ValueError(
                    f"outgoing schema version {requested!r} is not in the supported "
                    f"set {sorted(supported)} (R14.5)"
                )
            return requested
        # Default to the highest supported version (deterministic).
        return sorted(supported)[-1]

    # -- accessors ---------------------------------------------------------

    @property
    def schema_version(self) -> str:
        """The schema-version identifier stamped on every outgoing message."""
        return self._schema_version

    @property
    def credential(self) -> ServiceAccountCredential:
        """The read-only Service_Account credential attached to engine requests."""
        return self._credential

    # -- stamping ----------------------------------------------------------

    def stamp(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        """Return a copy of ``payload`` with the ``schemaVersion`` field set (R14.1)."""
        stamped = dict(payload)
        stamped["schemaVersion"] = self._schema_version
        return stamped

    def stamp_report(self, report: Triage_Report) -> Triage_Report:
        """Return ``report`` with its ``schemaVersion`` set to the outgoing version.

        ``Triage_Report`` is frozen, so this produces a validated copy rather than
        mutating in place.
        """
        if report.schemaVersion == self._schema_version:
            return report
        return report.model_copy(update={"schemaVersion": self._schema_version})

    # -- engine-directed request headers (R14.1, R14.2) --------------------

    def engine_request_headers(
        self, extra: Optional[Mapping[str, str]] = None
    ) -> dict[str, str]:
        """Headers for an engine-directed request: auth credential + schema version.

        Attaches the read-only ``Service_Account`` credential (R14.2) and the
        schema-version header (R14.1). Used for report emission, decision
        acknowledgements, and Read_Tool calls alike.
        """
        headers: dict[str, str] = dict(self._credential.auth_headers())
        headers[SCHEMA_VERSION_HEADER] = self._schema_version
        if extra:
            headers.update(extra)
        return headers


# ---------------------------------------------------------------------------
# Outbound report emission channel (R10.1, R14.1)
# ---------------------------------------------------------------------------


@runtime_checkable
class ReportTransport(Protocol):
    """Pluggable send transport for a report (real HTTP client wired at task 20.3)."""

    def send(self, report: Triage_Report, *, headers: Mapping[str, str]) -> None:
        """Deliver a stamped ``Triage_Report`` with the given request headers."""
        ...


class InMemoryReportTransport:
    """A testable :class:`ReportTransport` that records what it was asked to send."""

    def __init__(self) -> None:
        self._sent: list[tuple[Triage_Report, dict[str, str]]] = []

    def send(self, report: Triage_Report, *, headers: Mapping[str, str]) -> None:
        self._sent.append((report, dict(headers)))

    @property
    def sent(self) -> list[tuple[Triage_Report, dict[str, str]]]:
        """The (report, headers) pairs sent so far, in emission order."""
        return list(self._sent)


@runtime_checkable
class ReportEmitter(Protocol):
    """Interface for emitting a ``Triage_Report`` to the Control_Tower."""

    def emit(self, report: Triage_Report) -> Triage_Report:
        """Stamp and send ``report``; return the stamped report actually sent."""
        ...


class StampingReportEmitter:
    """Default :class:`ReportEmitter`: stamp the report, then hand it to a transport.

    The stamper attaches the schema version (R14.1) and the transport carries the
    read-only ``Service_Account`` credential via the stamper's engine-request
    headers (R14.2). The transport defaults to an in-memory double; task 20.3
    injects a real HTTP client.
    """

    def __init__(
        self,
        stamper: Optional[MessageStamper] = None,
        transport: Optional[ReportTransport] = None,
    ) -> None:
        self._stamper = stamper or MessageStamper()
        self._transport = transport or InMemoryReportTransport()

    def emit(self, report: Triage_Report) -> Triage_Report:
        stamped = self._stamper.stamp_report(report)
        headers = self._stamper.engine_request_headers()
        self._transport.send(stamped, headers=headers)
        return stamped

    @property
    def transport(self) -> ReportTransport:
        """The underlying send transport (the default in-memory double, or injected)."""
        return self._transport

    @property
    def stamper(self) -> MessageStamper:
        return self._stamper


# ---------------------------------------------------------------------------
# Inbound Control_Tower decision channel wiring point (R11)
# ---------------------------------------------------------------------------


class ControlTowerDecision(str, Enum):
    """A Control_Tower approver's decision on a paused investigation (R11)."""

    APPROVE = "approve"
    REJECT = "reject"


@dataclass(frozen=True)
class DecisionMessage:
    """A structured Control_Tower decision for a paused investigation (R11.3-R11.5).

    Mirrors the design's decision body:
    ``{ approverId, decision: "approve" | "reject", stepUpAuthenticated }``.
    Dual-control (two distinct ``approverId``s) and checkpoint resume are enforced
    by the HITL manager (task 19); this record is the transport-agnostic message
    the decision channel receives.
    """

    alertId: str
    approverId: str
    decision: ControlTowerDecision
    stepUpAuthenticated: bool = False


@dataclass(frozen=True)
class DecisionAck:
    """Acknowledgement returned when a decision is received at the wiring point."""

    alertId: str
    accepted: bool
    detail: Optional[str] = None


@runtime_checkable
class DecisionHandler(Protocol):
    """Wiring-point interface for the Control_Tower decision channel.

    The HITL resume manager (task 19) implements this to record the decision,
    enforce dual-control (R11.4), and resume/reject the checkpointed investigation
    (R11.3, R11.5). Until then, :class:`InMemoryDecisionChannel` records decisions.
    """

    def handle_decision(self, decision: DecisionMessage) -> DecisionAck:
        """Handle one Control_Tower decision and acknowledge receipt."""
        ...


class InMemoryDecisionChannel:
    """Default :class:`DecisionHandler` that records decisions until task 19 wires HITL."""

    def __init__(self) -> None:
        self._received: list[DecisionMessage] = []

    def handle_decision(self, decision: DecisionMessage) -> DecisionAck:
        self._received.append(decision)
        return DecisionAck(
            alertId=decision.alertId,
            accepted=True,
            detail="decision recorded; HITL resume is wired in task 19",
        )

    @property
    def received(self) -> list[DecisionMessage]:
        """The decisions received so far, in arrival order."""
        return list(self._received)


def parse_decision(alert_id: str, body: Mapping[str, Any]) -> DecisionMessage:
    """Validate a decoded decision body into a :class:`DecisionMessage`.

    Raises ``ValueError`` on a missing/invalid ``approverId`` or ``decision`` so a
    transport shim (task 20.3) can map the failure onto a 400. ``stepUpAuthenticated``
    defaults to ``False`` when absent.
    """

    if not isinstance(body, Mapping):
        raise ValueError("decision body must be a JSON object")
    if not alert_id or not str(alert_id).strip():
        raise ValueError("alertId path parameter is required")

    approver_id = body.get("approverId")
    if not isinstance(approver_id, str) or not approver_id.strip():
        raise ValueError("approverId is required and must be a non-empty string")

    raw_decision = body.get("decision")
    try:
        decision = ControlTowerDecision(raw_decision)
    except ValueError as exc:
        permitted = [d.value for d in ControlTowerDecision]
        raise ValueError(
            f"decision must be one of {permitted}; got {raw_decision!r}"
        ) from exc

    step_up = bool(body.get("stepUpAuthenticated", False))
    return DecisionMessage(
        alertId=str(alert_id),
        approverId=approver_id,
        decision=decision,
        stepUpAuthenticated=step_up,
    )


__all__ = [
    # stamping
    "SCHEMA_VERSION_HEADER",
    "MessageStamper",
    # report emission channel
    "ReportTransport",
    "InMemoryReportTransport",
    "ReportEmitter",
    "StampingReportEmitter",
    # decision channel wiring point
    "ControlTowerDecision",
    "DecisionMessage",
    "DecisionAck",
    "DecisionHandler",
    "InMemoryDecisionChannel",
    "parse_decision",
]
