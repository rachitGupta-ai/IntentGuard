"""Unit tests for the read-only Read_Tool layer (task 6.1).

Covers: exactly-five-tools registration with no write tools, per-tool timeout
-> gap, error -> gap, no-data -> gap, correlation result caps (<=100),
query_audit_history actor-scoping and <=30-day window clamping, and record-id
tagging with Untrusted_Content classification.
"""

from __future__ import annotations

from typing import Sequence

import pytest

from sidecar.config import SidecarConfig
from sidecar.tools import (
    READ_TOOL_NAMES,
    EngineRecord,
    InlineTimeoutRunner,
    ReadToolRegistry,
    StaticEngineBackend,
    ToolTimeout,
    build_registry,
)

_ALERT_TS_MS = 1_700_000_000_000
_MS_PER_DAY = 86_400 * 1000


def _records(n: int, prefix: str = "ar") -> list[EngineRecord]:
    return [EngineRecord(auditRecordId=f"{prefix}-{i}", summary=f"row {i}") for i in range(n)]


def _registry(**backend_kwargs) -> ReadToolRegistry:
    backend = StaticEngineBackend(**backend_kwargs)
    return build_registry(backend, runner=InlineTimeoutRunner())


# --- registration / read-only structure ----------------------------------


def test_registry_contains_exactly_the_five_read_tools():
    reg = _registry()
    assert reg.tool_names() == READ_TOOL_NAMES
    assert set(reg.tool_names()) == {
        "get_session_history",
        "get_actor_profile",
        "query_audit_history",
        "get_related_alerts",
        "get_exfil_correlations",
    }
    assert len(reg.tool_names()) == 5


@pytest.mark.parametrize(
    "write_tool",
    ["block_session", "enforce_policy", "write_audit", "mutate_state", "approve_action"],
)
def test_registry_has_no_write_block_or_enforcement_tools(write_tool):
    reg = _registry()
    assert reg.has_tool(write_tool) is False


def test_registry_membership_checks_for_in_set_tools():
    reg = _registry()
    for name in READ_TOOL_NAMES:
        assert reg.has_tool(name) is True


# --- record-id tagging + untrusted classification (R5.4, R10.3, R12.1) ----


def test_returned_evidence_is_tagged_with_record_id_and_marked_untrusted():
    reg = _registry(session_history=_records(3, prefix="sess"))
    result = reg.get_session_history(alert_id="alert-1")
    assert result.ok
    assert result.gap is None
    assert len(result.evidence) == 3
    for item in result.evidence:
        assert item.auditRecordId.startswith("sess-")
        assert item.sourceContentUntrusted is True
        assert item.kind == "context"


def test_actor_profile_evidence_tagged_and_untrusted():
    reg = _registry(actor_profile=_records(1, prefix="prof"))
    result = reg.get_actor_profile(actor_id="actor-9")
    assert result.ok
    assert result.evidence[0].auditRecordId == "prof-0"
    assert result.evidence[0].sourceContentUntrusted is True


# --- gap on no-data / error / timeout (R5.3, R6.5, R8.8) ------------------


def test_no_data_produces_gap_without_raising():
    reg = _registry(session_history=[])
    result = reg.get_session_history(alert_id="alert-1")
    assert result.evidence == ()
    assert result.gap is not None
    assert result.gap.element == "get_session_history"
    assert result.gap.stage == "context"
    assert "no data" in result.gap.reason.lower()


def test_backend_error_produces_gap_without_raising():
    class Boom(StaticEngineBackend):
        def get_actor_profile(self, actor_id: str):
            raise RuntimeError("engine unavailable")

    reg = ReadToolRegistry(Boom(), runner=InlineTimeoutRunner())
    result = reg.get_actor_profile(actor_id="actor-1")
    assert result.evidence == ()
    assert result.gap is not None
    assert "error" in result.gap.reason.lower()


def test_timeout_produces_gap_without_raising():
    class AlwaysTimeout(InlineTimeoutRunner):
        def run(self, func, timeout_seconds):
            raise ToolTimeout("simulated overrun")

    backend = StaticEngineBackend(session_history=_records(2))
    reg = ReadToolRegistry(backend, runner=AlwaysTimeout())
    result = reg.get_session_history(alert_id="alert-1")
    assert result.evidence == ()
    assert result.gap is not None
    assert "timeout" in result.gap.reason.lower()


def test_unbindable_records_are_excluded_and_recorded_as_partial_gap():
    records = [
        EngineRecord(auditRecordId="ok-1", summary="kept"),
        EngineRecord(auditRecordId="", summary="unbindable"),
    ]
    reg = _registry(related_alerts=records)
    result = reg.get_related_alerts(actor_id="a", session_id="s")
    assert len(result.evidence) == 1
    assert result.evidence[0].auditRecordId == "ok-1"
    assert result.gap is not None
    assert "record id" in result.gap.reason.lower()


def test_all_unbindable_records_yield_gap_and_no_evidence():
    records = [EngineRecord(auditRecordId="", summary="x")]
    reg = _registry(exfil_correlations=records)
    result = reg.get_exfil_correlations(actor_id="a", session_id="s")
    assert result.evidence == ()
    assert result.gap is not None


# --- correlation result caps <=100 (R6.1, R6.2) ---------------------------


def test_related_alerts_capped_at_configured_maximum():
    reg = _registry(related_alerts=_records(250, prefix="rel"))
    result = reg.get_related_alerts(actor_id="a", session_id="s")
    assert len(result.evidence) == 100


def test_exfil_correlations_capped_at_configured_maximum():
    reg = _registry(exfil_correlations=_records(150, prefix="exf"))
    result = reg.get_exfil_correlations(actor_id="a", session_id="s")
    assert len(result.evidence) == 100


def test_result_cap_honours_custom_config():
    backend = StaticEngineBackend(related_alerts=_records(40, prefix="rel"))
    config = SidecarConfig(correlation_result_cap=10)
    reg = build_registry(backend, config=config, runner=InlineTimeoutRunner())
    result = reg.get_related_alerts(actor_id="a", session_id="s")
    assert len(result.evidence) == 10


# --- query_audit_history actor-scoping + window clamp (R6.3) --------------


def test_audit_history_scoped_to_actor_and_default_30_day_window():
    backend = StaticEngineBackend(audit_history=_records(2, prefix="aud"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    result = reg.query_audit_history(actor_id="actor-7", alert_timestamp_ms=_ALERT_TS_MS)
    assert result.ok
    method, kwargs = backend.calls[-1]
    assert method == "query_audit_history"
    assert kwargs["actor_id"] == "actor-7"
    assert kwargs["to_ms"] == _ALERT_TS_MS
    span_ms = kwargs["to_ms"] - kwargs["from_ms"]
    assert span_ms == 30 * _MS_PER_DAY
    assert span_ms <= 30 * _MS_PER_DAY


def test_audit_history_clamps_overlong_requested_window():
    backend = StaticEngineBackend(audit_history=_records(1, prefix="aud"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    # Request a start 90 days before the alert — must clamp to 30 days.
    requested_from = _ALERT_TS_MS - 90 * _MS_PER_DAY
    reg.query_audit_history(
        actor_id="actor-1",
        alert_timestamp_ms=_ALERT_TS_MS,
        requested_from_ms=requested_from,
    )
    _, kwargs = backend.calls[-1]
    span_ms = kwargs["to_ms"] - kwargs["from_ms"]
    assert span_ms <= 30 * _MS_PER_DAY
    assert kwargs["from_ms"] == _ALERT_TS_MS - 30 * _MS_PER_DAY


def test_audit_history_honours_narrower_requested_window():
    backend = StaticEngineBackend(audit_history=_records(1, prefix="aud"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    requested_from = _ALERT_TS_MS - 5 * _MS_PER_DAY
    reg.query_audit_history(
        actor_id="actor-1",
        alert_timestamp_ms=_ALERT_TS_MS,
        requested_from_ms=requested_from,
    )
    _, kwargs = backend.calls[-1]
    assert kwargs["from_ms"] == requested_from
    assert kwargs["to_ms"] - kwargs["from_ms"] == 5 * _MS_PER_DAY


def test_audit_history_invalid_window_falls_back_to_full_window():
    backend = StaticEngineBackend(audit_history=_records(1, prefix="aud"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    # Requested start after the alert timestamp is invalid.
    reg.query_audit_history(
        actor_id="actor-1",
        alert_timestamp_ms=_ALERT_TS_MS,
        requested_from_ms=_ALERT_TS_MS + 10_000,
    )
    _, kwargs = backend.calls[-1]
    assert kwargs["from_ms"] == _ALERT_TS_MS - 30 * _MS_PER_DAY
    assert kwargs["from_ms"] <= kwargs["to_ms"]
