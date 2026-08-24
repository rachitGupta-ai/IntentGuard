"""Unit tests for the inbound Integration_Contract handler (task 3.1).

Covers the 201/200/202/400/409 response shapes, schema-version validation
ordering (version checked before contents), and rejected-message recording.
"""

from __future__ import annotations

import pytest

from sidecar.config import SidecarConfig
from sidecar.contract import (
    AdmissionResult,
    AdmissionState,
    InboundAlertHandler,
    InboundStatus,
    InMemoryRejectionRecorder,
    RejectionReason,
    TriggerDecision,
)
from sidecar.models import (
    AlertEnvelope,
    Evidence_Item,
    ThreatCategory,
    Triage_Report,
    TriggerType,
    VerdictValue,
)


def _valid_message(**overrides):
    msg = {
        "schemaVersion": "v1",
        "alertId": "alert-123",
        "triggerType": TriggerType.CANARY_TOKEN.value,
        "actorId": "actor-1",
        "sessionId": "session-1",
        "alertTimestampMs": 1_700_000_000_000,
        "divergenceScore": None,
        "signalPayload": {"foo": "bar"},
    }
    msg.update(overrides)
    return msg


def _report(alert_id: str = "alert-123") -> Triage_Report:
    return Triage_Report(
        alertId=alert_id,
        triggerType=TriggerType.CANARY_TOKEN,
        verdict=VerdictValue.BENIGN,
        confidence=0.5,
        threatCategory=ThreatCategory.BENIGN_ANOMALY,
        evidence=[
            Evidence_Item(auditRecordId="rec-1", kind="context", summary="ok"),
        ],
        schemaVersion="v1",
    )


class _StubAdmitter:
    def __init__(self, result: AdmissionResult) -> None:
        self._result = result

    def admit(self, envelope: AlertEnvelope) -> AdmissionResult:  # noqa: ARG002
        return self._result


# --- 201: new run admitted --------------------------------------------------


def test_new_alert_returns_201_accepted():
    handler = InboundAlertHandler()
    resp = handler.handle(_valid_message())

    assert resp.status is InboundStatus.CREATED
    assert resp.status_code == 201
    assert resp.body == {"alertId": "alert-123", "status": "accepted"}


# --- 202: in-progress -------------------------------------------------------


def test_in_progress_alert_returns_202():
    handler = InboundAlertHandler(
        idempotency_admitter=_StubAdmitter(AdmissionResult(state=AdmissionState.IN_PROGRESS))
    )
    resp = handler.handle(_valid_message())

    assert resp.status is InboundStatus.ACCEPTED
    assert resp.body == {"alertId": "alert-123", "status": "in_progress"}


# --- 200: completed within retention ---------------------------------------


def test_completed_alert_returns_200_with_report():
    report = _report()
    handler = InboundAlertHandler(
        idempotency_admitter=_StubAdmitter(
            AdmissionResult(state=AdmissionState.COMPLETED, report=report)
        )
    )
    resp = handler.handle(_valid_message())

    assert resp.status is InboundStatus.OK
    assert resp.report is report
    assert resp.body["alertId"] == "alert-123"
    assert resp.body["verdict"] == VerdictValue.BENIGN.value


# --- 409: absent / unsupported schema version ------------------------------


def test_absent_schema_version_returns_409_and_records():
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(rejection_recorder=recorder)
    msg = _valid_message()
    del msg["schemaVersion"]

    resp = handler.handle(msg)

    assert resp.status is InboundStatus.CONFLICT
    assert resp.body["error"] == "unsupported_version"
    assert resp.body["declaredVersion"] is None
    assert resp.body["supportedVersions"] == ["v1"]
    # Rejected message recorded before processing contents.
    assert len(recorder.records) == 1
    assert recorder.records[0].reason is RejectionReason.UNSUPPORTED_VERSION
    assert recorder.records[0].declaredVersion is None


def test_unsupported_schema_version_returns_409_naming_versions():
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(rejection_recorder=recorder)

    resp = handler.handle(_valid_message(schemaVersion="v99"))

    assert resp.status is InboundStatus.CONFLICT
    assert resp.body["declaredVersion"] == "v99"
    assert resp.body["supportedVersions"] == ["v1"]
    assert recorder.records[0].declaredVersion == "v99"


def test_version_checked_before_contents():
    """An unsupported version is rejected even when the rest of the body is junk."""
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(rejection_recorder=recorder)

    # Missing alertId/triggerType would normally be a 400, but version wins.
    resp = handler.handle({"schemaVersion": "v99", "garbage": True})

    assert resp.status is InboundStatus.CONFLICT
    assert recorder.records[0].reason is RejectionReason.UNSUPPORTED_VERSION


def test_multiple_supported_versions_listed_sorted():
    config = SidecarConfig(supported_schema_versions=frozenset({"v2", "v1"}))
    handler = InboundAlertHandler(config=config)

    resp = handler.handle(_valid_message(schemaVersion="v9"))

    assert resp.body["supportedVersions"] == ["v1", "v2"]


# --- 400: missing / invalid required field ---------------------------------


def test_missing_alert_id_returns_400_naming_field():
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(rejection_recorder=recorder)
    msg = _valid_message()
    del msg["alertId"]

    resp = handler.handle(msg)

    assert resp.status is InboundStatus.BAD_REQUEST
    assert resp.body["missingField"] == "alertId"
    assert recorder.records[0].reason is RejectionReason.MISSING_FIELD
    assert recorder.records[0].field == "alertId"


def test_empty_alert_id_returns_400():
    handler = InboundAlertHandler()
    resp = handler.handle(_valid_message(alertId=""))

    assert resp.status is InboundStatus.BAD_REQUEST
    assert resp.body["missingField"] == "alertId"


def test_invalid_trigger_type_returns_400():
    handler = InboundAlertHandler()
    resp = handler.handle(_valid_message(triggerType="NOT_A_TRIGGER"))

    assert resp.status is InboundStatus.BAD_REQUEST
    assert resp.body["missingField"] == "triggerType"


def test_non_object_message_returns_400_and_records_malformed():
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(rejection_recorder=recorder)

    resp = handler.handle("not-an-object")

    assert resp.status is InboundStatus.BAD_REQUEST
    assert resp.body["error"] == "malformed"
    assert recorder.records[0].reason is RejectionReason.MALFORMED


# --- 400: trigger classifier rejects ---------------------------------------


class _RejectingClassifier:
    def classify(self, envelope: AlertEnvelope) -> TriggerDecision:  # noqa: ARG002
        return TriggerDecision(admitted=False, missingField="divergenceScore",
                               detail="block-range trigger requires a divergence score")


def test_trigger_classifier_rejection_returns_400():
    recorder = InMemoryRejectionRecorder()
    handler = InboundAlertHandler(
        trigger_classifier=_RejectingClassifier(), rejection_recorder=recorder
    )

    resp = handler.handle(_valid_message())

    assert resp.status is InboundStatus.BAD_REQUEST
    assert resp.body["missingField"] == "divergenceScore"
    assert recorder.records[0].field == "divergenceScore"


def test_completed_without_report_raises():
    handler = InboundAlertHandler(
        idempotency_admitter=_StubAdmitter(AdmissionResult(state=AdmissionState.COMPLETED))
    )
    with pytest.raises(ValueError):
        handler.handle(_valid_message())
