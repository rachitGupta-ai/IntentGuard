"""Inbound Integration_Contract handling for the Alert Triage Sidecar.

This module implements the sidecar side of the versioned inbound contract
(``POST /triage/v1/alerts`` in the design). It is deliberately transport-agnostic:
``InboundAlertHandler.handle`` takes an already-decoded message (a mapping) and
returns a structured ``InboundResponse``. A thin HTTP/queue shim can map that
response onto a framework at wiring time (task 20.3) without this logic knowing
anything about the transport.

Responsibilities of task 3.1 (this module):

  * Parse an incoming alert message.
  * Validate ``schemaVersion`` against the supported set *first*, rejecting an
    absent/unsupported version with a ``409`` version error naming the declared
    version and the supported set, and recording the rejected message **before
    any content is processed** (R14.5).
  * Parse the payload into an ``AlertEnvelope``; a missing/invalid required field
    yields a ``400`` naming the field, recorded as a rejection (R2.6, R4.2).
  * Return the ``201 / 200 / 202`` responses defined in the design by delegating
    trigger classification and idempotency admission to **injected** interfaces
    (filled in by tasks 4 and 5, wired together in task 20.3).

Decoupling / injection points:

  * ``TriggerClassifier``  - decides whether a parsed envelope is an admissible
    trigger (task 4). The default admits every parsed envelope so this handler
    is exercisable in isolation.
  * ``IdempotencyAdmitter`` - decides new / in-progress / completed for an
    ``alertId`` (task 5). The default admits every envelope as a new run.
  * ``RejectionRecorder``  - records rejected messages (see ``rejection.py``).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, IntEnum
from typing import Any, Mapping, Optional, Protocol, runtime_checkable

from pydantic import ValidationError

from sidecar.config import SidecarConfig, get_config
from sidecar.contract.rejection import (
    InMemoryRejectionRecorder,
    RejectedMessage,
    RejectionReason,
    RejectionRecorder,
)
from sidecar.models import AlertEnvelope, Triage_Report


# ---------------------------------------------------------------------------
# Response types
# ---------------------------------------------------------------------------


class InboundStatus(IntEnum):
    """HTTP-style status codes the inbound handler returns (design contract)."""

    OK = 200                 # completed within retention -> existing report (R3.2, R3.6)
    CREATED = 201            # new run admitted (R2, R3.5)
    ACCEPTED = 202           # active run already exists (R3.3)
    BAD_REQUEST = 400        # missing/invalid required field (R2.6, R4.2)
    CONFLICT = 409           # absent/unsupported schema version (R14.5)


@dataclass(frozen=True)
class InboundResponse:
    """A transport-agnostic response from the inbound handler.

    ``status`` is the status code; ``body`` is the JSON-serializable payload the
    design specifies for that code. For a ``200`` response the originating
    ``Triage_Report`` is also exposed via ``report`` for callers that prefer the
    typed object over the serialized ``body``.
    """

    status: InboundStatus
    body: Mapping[str, Any]
    report: Optional[Triage_Report] = None

    @property
    def status_code(self) -> int:
        """The numeric status code (convenience for transport shims)."""
        return int(self.status)


# ---------------------------------------------------------------------------
# Injection point: trigger classification (task 4)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class TriggerDecision:
    """Outcome of classifying a parsed envelope as a trigger.

    ``admitted`` is ``True`` when the envelope carries an admissible
    ``Triage_Trigger`` and should proceed to idempotency admission. When
    ``False`` the envelope is rejected as malformed and ``missingField`` names
    the missing/invalid required trigger field (R2.6).
    """

    admitted: bool
    missingField: Optional[str] = None
    detail: Optional[str] = None


@runtime_checkable
class TriggerClassifier(Protocol):
    """Injection point implemented by the trigger classifier (task 4)."""

    def classify(self, envelope: AlertEnvelope) -> TriggerDecision:
        """Classify a parsed envelope as an admissible trigger or reject it."""
        ...


class _AdmitAllTriggerClassifier:
    """Default classifier used until task 4 is wired in.

    Admits every parsed envelope. Trigger-condition and malformed-field logic is
    added by the real classifier (task 4); keeping the default permissive lets
    this handler be tested in isolation for parsing/versioning/response shape.
    """

    def classify(self, envelope: AlertEnvelope) -> TriggerDecision:  # noqa: ARG002
        return TriggerDecision(admitted=True)


# ---------------------------------------------------------------------------
# Injection point: idempotency admission (task 5)
# ---------------------------------------------------------------------------


class AdmissionState(str, Enum):
    """The idempotency admission outcome for an ``alertId`` (R3)."""

    NEW = "new"                 # admit a new run -> 201 (R3.4, R3.5)
    IN_PROGRESS = "in_progress" # active run exists -> 202 (R3.3)
    COMPLETED = "completed"     # completed within retention -> 200 report (R3.2, R3.6)


@dataclass(frozen=True)
class AdmissionResult:
    """Result of an idempotency admission decision.

    ``report`` is required (and only present) when ``state`` is ``COMPLETED``.
    """

    state: AdmissionState
    report: Optional[Triage_Report] = None


@runtime_checkable
class IdempotencyAdmitter(Protocol):
    """Injection point implemented by the idempotency store (task 5)."""

    def admit(self, envelope: AlertEnvelope) -> AdmissionResult:
        """Decide new / in-progress / completed for the envelope's ``alertId``."""
        ...


class _AlwaysNewAdmitter:
    """Default admitter used until task 5 is wired in: every alert is a new run."""

    def admit(self, envelope: AlertEnvelope) -> AdmissionResult:  # noqa: ARG002
        return AdmissionResult(state=AdmissionState.NEW)


# ---------------------------------------------------------------------------
# The inbound handler
# ---------------------------------------------------------------------------

# Field names required on the raw envelope payload; used only to produce a
# helpful ``missingField`` when pydantic reports a missing/invalid field.
_ENVELOPE_REQUIRED_FIELDS = ("alertId", "triggerType", "alertTimestampMs", "signalPayload")


class InboundAlertHandler:
    """Handles inbound alert messages over the versioned Integration_Contract.

    Wire the real ``TriggerClassifier`` (task 4) and ``IdempotencyAdmitter``
    (task 5) via the constructor; both default to permissive stand-ins so this
    handler is usable and testable on its own. The ``RejectionRecorder`` defaults
    to an in-memory recorder.
    """

    def __init__(
        self,
        config: Optional[SidecarConfig] = None,
        *,
        trigger_classifier: Optional[TriggerClassifier] = None,
        idempotency_admitter: Optional[IdempotencyAdmitter] = None,
        rejection_recorder: Optional[RejectionRecorder] = None,
    ) -> None:
        self._config = config or get_config()
        self._classifier: TriggerClassifier = trigger_classifier or _AdmitAllTriggerClassifier()
        self._admitter: IdempotencyAdmitter = idempotency_admitter or _AlwaysNewAdmitter()
        self._recorder: RejectionRecorder = rejection_recorder or InMemoryRejectionRecorder()

    # -- public API --------------------------------------------------------

    def handle(self, message: Any) -> InboundResponse:
        """Process one inbound alert message and return a structured response.

        The order of checks is deliberate and matches the contract:

          1. The message must be a mapping (object). (else 400)
          2. ``schemaVersion`` must be present and supported, else the message is
             recorded and rejected *before its contents are processed* (409).
          3. The payload must parse into a valid ``AlertEnvelope`` (else 400).
          4. The (injected) trigger classifier must admit the envelope (else 400).
          5. The (injected) idempotency admitter decides 201 / 202 / 200.
        """

        # 1. Shape check: must be a decodable object/mapping.
        if not isinstance(message, Mapping):
            return self._reject_malformed(message)

        # 2. Schema-version validation FIRST, before processing any contents (R14.5).
        declared_version = message.get("schemaVersion")
        version_str = declared_version if isinstance(declared_version, str) else None
        if not self._config.is_supported_version(version_str):
            return self._reject_unsupported_version(message, declared_version)

        # 3. Parse and validate the envelope (R2.6, R4.2).
        try:
            envelope = AlertEnvelope.model_validate(dict(message))
        except ValidationError as exc:
            return self._reject_missing_field(message, exc)

        # 4. Trigger classification (injected; task 4).
        decision = self._classifier.classify(envelope)
        if not decision.admitted:
            return self._reject_trigger(message, decision)

        # 5. Idempotency admission (injected; task 5).
        return self._admit(envelope)

    # -- rejection paths ---------------------------------------------------

    def _reject_unsupported_version(
        self, message: Mapping[str, Any], declared_version: Any
    ) -> InboundResponse:
        """Record and reject an absent/unsupported schema version (R14.5, 409)."""
        declared_str = declared_version if isinstance(declared_version, str) else None
        supported = self._supported_versions_list()
        # Record the rejected message BEFORE processing its contents (R14.5).
        self._recorder.record(
            RejectedMessage(
                reason=RejectionReason.UNSUPPORTED_VERSION,
                detail=(
                    "schemaVersion is absent"
                    if declared_str is None
                    else f"schemaVersion '{declared_str}' is not supported"
                ),
                declaredVersion=declared_str,
                rawMessage=dict(message),
            )
        )
        return InboundResponse(
            status=InboundStatus.CONFLICT,
            body={
                "error": "unsupported_version",
                "declaredVersion": declared_str,
                "supportedVersions": supported,
            },
        )

    def _reject_missing_field(
        self, message: Mapping[str, Any], exc: ValidationError
    ) -> InboundResponse:
        """Record and reject a payload missing/with an invalid required field (400)."""
        missing_field = self._first_offending_field(exc)
        self._recorder.record(
            RejectedMessage(
                reason=RejectionReason.MISSING_FIELD,
                detail=f"required field '{missing_field}' is missing or invalid",
                declaredVersion=self._raw_version(message),
                field=missing_field,
                rawMessage=dict(message),
            )
        )
        return InboundResponse(
            status=InboundStatus.BAD_REQUEST,
            body={
                "error": "missing_field",
                "missingField": missing_field,
            },
        )

    def _reject_trigger(
        self, message: Mapping[str, Any], decision: TriggerDecision
    ) -> InboundResponse:
        """Record and reject a parsed-but-inadmissible trigger (R2.6, 400)."""
        missing_field = decision.missingField or "triggerType"
        self._recorder.record(
            RejectedMessage(
                reason=RejectionReason.MISSING_FIELD,
                detail=decision.detail
                or f"required trigger field '{missing_field}' is missing or invalid",
                declaredVersion=self._raw_version(message),
                field=missing_field,
                rawMessage=dict(message),
            )
        )
        return InboundResponse(
            status=InboundStatus.BAD_REQUEST,
            body={
                "error": "missing_field",
                "missingField": missing_field,
            },
        )

    def _reject_malformed(self, message: Any) -> InboundResponse:
        """Record and reject a message that is not a decodable object (400)."""
        raw = dict(message) if isinstance(message, Mapping) else {}
        self._recorder.record(
            RejectedMessage(
                reason=RejectionReason.MALFORMED,
                detail="inbound message is not a JSON object",
                rawMessage=raw,
            )
        )
        return InboundResponse(
            status=InboundStatus.BAD_REQUEST,
            body={"error": "malformed", "missingField": None},
        )

    # -- admission path ----------------------------------------------------

    def _admit(self, envelope: AlertEnvelope) -> InboundResponse:
        """Map an idempotency admission result onto a 201 / 202 / 200 response."""
        result = self._admitter.admit(envelope)

        if result.state is AdmissionState.NEW:
            return InboundResponse(
                status=InboundStatus.CREATED,
                body={"alertId": envelope.alertId, "status": "accepted"},
            )

        if result.state is AdmissionState.IN_PROGRESS:
            return InboundResponse(
                status=InboundStatus.ACCEPTED,
                body={"alertId": envelope.alertId, "status": "in_progress"},
            )

        # COMPLETED within retention -> return the existing report (R3.2, R3.6).
        report = result.report
        if report is None:  # defensive: a completed admission must carry a report
            raise ValueError("COMPLETED admission must include a Triage_Report")
        return InboundResponse(
            status=InboundStatus.OK,
            body=report.model_dump(),
            report=report,
        )

    # -- helpers -----------------------------------------------------------

    def _supported_versions_list(self) -> list[str]:
        """The supported schema versions as a sorted, JSON-friendly list."""
        return sorted(self._config.supported_schema_versions)

    @staticmethod
    def _raw_version(message: Mapping[str, Any]) -> Optional[str]:
        version = message.get("schemaVersion")
        return version if isinstance(version, str) else None

    @staticmethod
    def _first_offending_field(exc: ValidationError) -> str:
        """Best-effort extraction of the first missing/invalid field name.

        Prefers a known required envelope field so the ``missingField`` in the
        response is meaningful to the engine.
        """
        offending: list[str] = []
        for err in exc.errors():
            loc = err.get("loc", ())
            if loc:
                offending.append(str(loc[0]))
        for name in _ENVELOPE_REQUIRED_FIELDS:
            if name in offending:
                return name
        return offending[0] if offending else "unknown"


__all__ = [
    "InboundStatus",
    "InboundResponse",
    "TriggerDecision",
    "TriggerClassifier",
    "AdmissionState",
    "AdmissionResult",
    "IdempotencyAdmitter",
    "InboundAlertHandler",
]
