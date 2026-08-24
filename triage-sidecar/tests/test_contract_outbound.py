"""Unit tests for Service_Account auth and outgoing message stamping (task 3.3).

Covers:
  * the read-only Service_Account credential and its least-privilege guarantees
    (R1.7, R14.2);
  * the message stamper attaching the schema version to reports/payloads and the
    credential to engine-directed request headers (R14.1, R14.2);
  * the outbound report emission channel (stamp + transport) (R10.1, R14.1);
  * the Control_Tower decision channel wiring point + structured decision (R11).
"""

from __future__ import annotations

import pytest

from sidecar.config import SidecarConfig
from sidecar.contract import (
    SCHEMA_VERSION_HEADER,
    ControlTowerDecision,
    DecisionMessage,
    InMemoryDecisionChannel,
    InMemoryReportTransport,
    MessageStamper,
    ReadOnlyScope,
    ServiceAccountCredential,
    StampingReportEmitter,
    default_service_account,
    parse_decision,
)
from sidecar.contract.auth import READ_ONLY_SCOPES
from sidecar.models import (
    Evidence_Item,
    ThreatCategory,
    Triage_Report,
    TriggerType,
    VerdictValue,
)


def _report(*, schema_version: str = "v1", alert_id: str = "alert-1") -> Triage_Report:
    return Triage_Report(
        alertId=alert_id,
        triggerType=TriggerType.CANARY_TOKEN,
        verdict=VerdictValue.BENIGN,
        confidence=0.5,
        threatCategory=ThreatCategory.BENIGN_ANOMALY,
        evidence=[Evidence_Item(auditRecordId="rec-1", kind="context", summary="ok")],
        schemaVersion=schema_version,
    )


# --- Service_Account credential --------------------------------------------


def test_default_service_account_is_read_only_with_all_scopes():
    cred = default_service_account()
    assert cred.is_read_only
    assert cred.scopes == READ_ONLY_SCOPES
    # No forbidden markers anywhere in the granted scopes.
    for scope in cred.scopes:
        assert "write" not in scope.value
        assert "enforce" not in scope.value
        assert "block" not in scope.value


def test_credential_auth_header_uses_scheme_and_token():
    cred = ServiceAccountCredential(
        identity="triage-sidecar",
        token="secret-token",
        scopes=frozenset({ReadOnlyScope.READ_SESSION_HISTORY}),
    )
    assert cred.auth_headers() == {"Authorization": "Bearer secret-token"}
    assert cred.authorization_value == "Bearer secret-token"


def test_credential_rejects_non_read_only_scope():
    with pytest.raises(ValueError):
        ServiceAccountCredential(
            identity="triage-sidecar",
            token="t",
            scopes=frozenset({"write:config"}),  # type: ignore[arg-type]
        )


def test_credential_requires_identity_token_and_scopes():
    with pytest.raises(ValueError):
        ServiceAccountCredential(identity="", token="t")
    with pytest.raises(ValueError):
        ServiceAccountCredential(identity="id", token="")
    with pytest.raises(ValueError):
        ServiceAccountCredential(identity="id", token="t", scopes=frozenset())


def test_credential_expiry():
    cred = default_service_account(expiresAtMs=1_000)
    assert not cred.is_expired(999)
    assert cred.is_expired(1_000)
    assert cred.is_expired(2_000)
    # No expiry set -> never expired.
    assert not default_service_account().is_expired(10**18)


# --- MessageStamper ---------------------------------------------------------


def test_stamper_default_version_is_highest_supported():
    config = SidecarConfig(supported_schema_versions=frozenset({"v1", "v2"}))
    stamper = MessageStamper(config)
    assert stamper.schema_version == "v2"


def test_stamper_explicit_version_must_be_supported():
    config = SidecarConfig(supported_schema_versions=frozenset({"v1"}))
    with pytest.raises(ValueError):
        MessageStamper(config, schema_version="v9")
    assert MessageStamper(config, schema_version="v1").schema_version == "v1"


def test_stamp_payload_sets_schema_version():
    stamper = MessageStamper(SidecarConfig(supported_schema_versions=frozenset({"v1"})))
    stamped = stamper.stamp({"foo": "bar"})
    assert stamped == {"foo": "bar", "schemaVersion": "v1"}


def test_stamp_report_sets_outgoing_version():
    config = SidecarConfig(supported_schema_versions=frozenset({"v1", "v2"}))
    stamper = MessageStamper(config)  # highest -> v2
    stamped = stamper.stamp_report(_report(schema_version="v1"))
    assert stamped.schemaVersion == "v2"
    # already-current report is returned unchanged
    already = _report(schema_version="v2")
    assert stamper.stamp_report(already) is already


def test_engine_request_headers_attach_credential_and_version():
    config = SidecarConfig(supported_schema_versions=frozenset({"v1"}))
    stamper = MessageStamper(config)
    headers = stamper.engine_request_headers()
    assert headers["Authorization"] == stamper.credential.authorization_value
    assert headers[SCHEMA_VERSION_HEADER] == "v1"


def test_engine_request_headers_merge_extra():
    stamper = MessageStamper(SidecarConfig(supported_schema_versions=frozenset({"v1"})))
    headers = stamper.engine_request_headers({"X-Trace": "abc"})
    assert headers["X-Trace"] == "abc"
    assert "Authorization" in headers


# --- Outbound report emission channel --------------------------------------


def test_report_emitter_stamps_and_sends_with_auth():
    config = SidecarConfig(supported_schema_versions=frozenset({"v1", "v2"}))
    stamper = MessageStamper(config)  # v2
    transport = InMemoryReportTransport()
    emitter = StampingReportEmitter(stamper=stamper, transport=transport)

    returned = emitter.emit(_report(schema_version="v1"))

    assert returned.schemaVersion == "v2"
    assert len(transport.sent) == 1
    sent_report, sent_headers = transport.sent[0]
    assert sent_report.schemaVersion == "v2"
    assert sent_headers["Authorization"] == stamper.credential.authorization_value
    assert sent_headers[SCHEMA_VERSION_HEADER] == "v2"


def test_report_emitter_defaults_are_usable_standalone():
    emitter = StampingReportEmitter()
    returned = emitter.emit(_report(schema_version="v1"))
    assert returned.schemaVersion  # stamped
    assert isinstance(emitter.transport, InMemoryReportTransport)
    assert len(emitter.transport.sent) == 1


# --- Control_Tower decision channel wiring point ---------------------------


def test_in_memory_decision_channel_records_decisions():
    channel = InMemoryDecisionChannel()
    msg = DecisionMessage(
        alertId="alert-1",
        approverId="approver-1",
        decision=ControlTowerDecision.APPROVE,
        stepUpAuthenticated=True,
    )
    ack = channel.handle_decision(msg)
    assert ack.accepted
    assert ack.alertId == "alert-1"
    assert channel.received == [msg]


def test_parse_decision_valid():
    msg = parse_decision(
        "alert-1",
        {"approverId": "approver-1", "decision": "reject", "stepUpAuthenticated": True},
    )
    assert msg.alertId == "alert-1"
    assert msg.approverId == "approver-1"
    assert msg.decision is ControlTowerDecision.REJECT
    assert msg.stepUpAuthenticated is True


def test_parse_decision_defaults_step_up_false():
    msg = parse_decision("alert-1", {"approverId": "a", "decision": "approve"})
    assert msg.stepUpAuthenticated is False


@pytest.mark.parametrize(
    "alert_id, body",
    [
        ("alert-1", {"decision": "approve"}),               # missing approverId
        ("alert-1", {"approverId": "", "decision": "approve"}),  # empty approverId
        ("alert-1", {"approverId": "a", "decision": "maybe"}),   # invalid decision
        ("", {"approverId": "a", "decision": "approve"}),        # missing alertId
    ],
)
def test_parse_decision_invalid_raises(alert_id, body):
    with pytest.raises(ValueError):
        parse_decision(alert_id, body)
