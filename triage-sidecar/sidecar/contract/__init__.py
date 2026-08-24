"""Versioned Integration_Contract adapter for the Alert Triage Sidecar.

Public API (task 3.1 - inbound message handling and schema-version validation)
------------------------------------------------------------------------------
Handler entrypoint:
  * ``InboundAlertHandler``  - parses an inbound alert message, validates the
    ``schemaVersion`` against the supported set first (R14.5), parses the
    ``AlertEnvelope`` (R2.6, R4.2), and returns the design's 201/200/202/400/409
    responses. Its ``handle(message)`` method is transport-agnostic (takes a
    decoded mapping, returns an ``InboundResponse``).

Response types:
  * ``InboundResponse`` / ``InboundStatus`` - the structured response + code.

Injection points (wired by later tasks, together in task 20.3):
  * ``TriggerClassifier`` / ``TriggerDecision``      - trigger admission (task 4).
  * ``IdempotencyAdmitter`` / ``AdmissionResult`` / ``AdmissionState`` - idempotency (task 5).
  * ``RejectionRecorder`` / ``RejectedMessage`` / ``RejectionReason`` - rejection recording.

Public API (task 3.3 - Service_Account auth + outgoing message stamping)
------------------------------------------------------------------------
Service_Account credential (read-only, attached to every engine-directed request):
  * ``ServiceAccountCredential`` / ``ReadOnlyScope`` / ``READ_ONLY_SCOPES`` /
    ``default_service_account`` - the least-privileged credential and its scopes (R1.7, R14.2).

Outgoing message stamping (schema version on every outgoing message, R14.1):
  * ``MessageStamper``            - stamps reports/payloads with ``schemaVersion`` and
    produces engine-directed request headers (auth credential + version).
  * ``SCHEMA_VERSION_HEADER``     - the request header carrying the schema version.

Outbound report emission channel (sidecar -> Control_Tower, R10.1):
  * ``ReportEmitter`` (Protocol) / ``StampingReportEmitter`` (default) - emit a
    stamped ``Triage_Report``.
  * ``ReportTransport`` (Protocol) / ``InMemoryReportTransport`` - the pluggable
    send transport injection point (real HTTP client wired at task 20.3).

Inbound Control_Tower decision channel wiring point (R11; HITL resume at task 19):
  * ``DecisionMessage`` / ``ControlTowerDecision`` / ``DecisionAck`` - the structured
    approve/reject decision.
  * ``DecisionHandler`` (Protocol) / ``InMemoryDecisionChannel`` (default) / ``parse_decision``.
"""

from sidecar.contract.auth import (
    FORBIDDEN_SCOPE_MARKERS,
    READ_ONLY_SCOPES,
    ReadOnlyScope,
    ServiceAccountCredential,
    default_service_account,
)
from sidecar.contract.inbound import (
    AdmissionResult,
    AdmissionState,
    IdempotencyAdmitter,
    InboundAlertHandler,
    InboundResponse,
    InboundStatus,
    TriggerClassifier,
    TriggerDecision,
)
from sidecar.contract.outbound import (
    SCHEMA_VERSION_HEADER,
    ControlTowerDecision,
    DecisionAck,
    DecisionHandler,
    DecisionMessage,
    InMemoryDecisionChannel,
    InMemoryReportTransport,
    MessageStamper,
    ReportEmitter,
    ReportTransport,
    StampingReportEmitter,
    parse_decision,
)
from sidecar.contract.rejection import (
    InMemoryRejectionRecorder,
    RejectedMessage,
    RejectionReason,
    RejectionRecorder,
)

__all__ = [
    # handler + responses
    "InboundAlertHandler",
    "InboundResponse",
    "InboundStatus",
    # trigger classification injection point (task 4)
    "TriggerClassifier",
    "TriggerDecision",
    # idempotency injection point (task 5)
    "IdempotencyAdmitter",
    "AdmissionResult",
    "AdmissionState",
    # rejection recording
    "RejectionRecorder",
    "RejectedMessage",
    "RejectionReason",
    "InMemoryRejectionRecorder",
    # Service_Account credential (task 3.3)
    "ServiceAccountCredential",
    "ReadOnlyScope",
    "READ_ONLY_SCOPES",
    "FORBIDDEN_SCOPE_MARKERS",
    "default_service_account",
    # outgoing message stamping (task 3.3)
    "MessageStamper",
    "SCHEMA_VERSION_HEADER",
    # outbound report emission channel (task 3.3)
    "ReportEmitter",
    "StampingReportEmitter",
    "ReportTransport",
    "InMemoryReportTransport",
    # Control_Tower decision channel wiring point (task 3.3)
    "ControlTowerDecision",
    "DecisionMessage",
    "DecisionAck",
    "DecisionHandler",
    "InMemoryDecisionChannel",
    "parse_decision",
]
