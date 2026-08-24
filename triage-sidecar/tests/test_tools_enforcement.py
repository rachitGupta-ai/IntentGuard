"""Unit tests for read-only enforcement of out-of-set tool requests (task 6.2).

Covers: in-set tools are allowed and delegate to the registry; out-of-set /
adversarial / injected requests are denied *before* execution with no backend
call made; each denial produces a mandatory DeniedInvocation and (by default) a
refusal Evidence_Item recording the requested action and its source; refusal
record-id handling (synthesised vs caller-supplied); and refusal-evidence
suppression.

Validates: Requirements 1.3, 8.6, 8.9, 12.4, 12.5
"""

from __future__ import annotations

import pytest

from sidecar.models import DeniedInvocation, Evidence_Item
from sidecar.tools import (
    DENIAL_REASON,
    READ_TOOL_NAMES,
    EngineRecord,
    GuardedInvocation,
    InlineTimeoutRunner,
    ReadOnlyEnforcer,
    ReadToolRegistry,
    StaticEngineBackend,
    build_registry,
)


def _records(n: int, prefix: str = "ar") -> list[EngineRecord]:
    return [EngineRecord(auditRecordId=f"{prefix}-{i}", summary=f"row {i}") for i in range(n)]


def _enforcer(**backend_kwargs) -> ReadOnlyEnforcer:
    backend = StaticEngineBackend(**backend_kwargs)
    registry = build_registry(backend, runner=InlineTimeoutRunner())
    return ReadOnlyEnforcer(registry)


# --- allow path: in-set tools execute via the registry --------------------


def test_in_set_tool_is_allowed_and_delegates_to_registry():
    enforcer = _enforcer(session_history=_records(2, prefix="sess"))
    result = enforcer.guarded_invoke(
        "get_session_history", source="probe", args={"alert_id": "alert-1"}
    )
    assert isinstance(result, GuardedInvocation)
    assert result.allowed is True
    assert result.denied is False
    assert result.denial is None
    assert result.refusal_evidence is None
    assert result.tool_result is not None
    assert result.tool_result.ok
    assert len(result.tool_result.evidence) == 2


def test_allowed_invocation_forwards_arguments_to_the_tool():
    backend = StaticEngineBackend(audit_history=_records(1, prefix="aud"))
    registry = build_registry(backend, runner=InlineTimeoutRunner())
    enforcer = ReadOnlyEnforcer(registry)
    enforcer.guarded_invoke(
        "query_audit_history",
        source="probe",
        args={"actor_id": "actor-7", "alert_timestamp_ms": 1_700_000_000_000},
    )
    method, kwargs = backend.calls[-1]
    assert method == "query_audit_history"
    assert kwargs["actor_id"] == "actor-7"


def test_every_in_set_tool_is_reported_as_available():
    enforcer = _enforcer()
    assert enforcer.tool_names() == READ_TOOL_NAMES
    for name in READ_TOOL_NAMES:
        assert enforcer.has_tool(name) is True


# --- deny path: out-of-set requests refused before execution --------------


@pytest.mark.parametrize(
    "bad_tool",
    [
        "block_session",
        "enforce_policy",
        "write_audit",
        "mutate_state",
        "approve_action",
        "delete_everything",
    ],
)
def test_out_of_set_tool_is_denied_and_recorded(bad_tool):
    enforcer = _enforcer()
    result = enforcer.guarded_invoke(bad_tool, source="untrusted_content")
    assert result.allowed is False
    assert result.denied is True
    assert result.tool_result is None
    assert isinstance(result.denial, DeniedInvocation)
    assert result.denial.requestedTool == bad_tool
    assert result.denial.source == "untrusted_content"
    assert result.denial.reason == DENIAL_REASON


def test_blank_tool_name_is_denied_and_recorded_with_placeholder():
    # An adversarial blank request must still be denied and recorded even
    # though the DeniedInvocation record requires a non-empty name.
    enforcer = _enforcer()
    result = enforcer.guarded_invoke("   ", source="untrusted_content")
    assert result.denied is True
    assert result.denial is not None
    assert result.denial.requestedTool.strip() != ""
    assert result.refusal_evidence is not None
    assert result.refusal_evidence.auditRecordId


def test_denied_request_records_refusal_evidence_by_default():
    enforcer = _enforcer()
    result = enforcer.guarded_invoke("block_session", source="probe")
    ev = result.refusal_evidence
    assert isinstance(ev, Evidence_Item)
    assert ev.kind == "refusal"
    assert ev.auditRecordId  # non-empty synthesised record id
    assert "block_session" in ev.summary
    assert "probe" in ev.summary
    # A refusal is the sidecar's own record, not fetched untrusted content.
    assert ev.sourceContentUntrusted is False


def test_denial_performs_no_backend_execution():
    backend = StaticEngineBackend(session_history=_records(3))
    registry = build_registry(backend, runner=InlineTimeoutRunner())
    enforcer = ReadOnlyEnforcer(registry)
    enforcer.guarded_invoke(
        "get_session_history_but_also_block",
        source="untrusted_content",
        args={"alert_id": "alert-1"},
    )
    # CRITICAL: no backend/tool call happened on the denial path.
    assert backend.calls == []


def test_injected_request_naming_a_real_tool_plus_suffix_is_denied():
    # An adversarial request that tries to smuggle in an out-of-set action by
    # decorating a real tool name is still not an exact in-set match.
    enforcer = _enforcer(session_history=_records(1))
    result = enforcer.guarded_invoke(
        "get_session_history; DROP TABLE audit", source="untrusted_content"
    )
    assert result.denied is True
    assert result.denial is not None


# --- refusal record-id handling ------------------------------------------


def test_caller_supplied_audit_record_id_is_bound_to_refusal_evidence():
    enforcer = _enforcer()
    result = enforcer.guarded_invoke(
        "enforce_policy",
        source="probe",
        audit_record_id="audit-42",
    )
    assert result.refusal_evidence is not None
    assert result.refusal_evidence.auditRecordId == "audit-42"


def test_refusal_record_id_factory_is_injectable_for_determinism():
    backend = StaticEngineBackend()
    registry = build_registry(backend, runner=InlineTimeoutRunner())
    enforcer = ReadOnlyEnforcer(
        registry,
        refusal_record_id_factory=lambda tool, source: f"refusal-{source}-{tool}",
    )
    result = enforcer.guarded_invoke("write_audit", source="probe")
    assert result.refusal_evidence is not None
    assert result.refusal_evidence.auditRecordId == "refusal-probe-write_audit"


def test_refusal_evidence_can_be_suppressed_but_denial_still_recorded():
    enforcer = _enforcer()
    result = enforcer.guarded_invoke(
        "mutate_state", source="probe", record_refusal_evidence=False
    )
    assert result.denied is True
    assert result.denial is not None  # mandatory record always present
    assert result.refusal_evidence is None  # suppressed where not applicable


def test_synthesised_refusal_record_ids_are_unique_across_denials():
    enforcer = _enforcer()
    a = enforcer.guarded_invoke("block_session", source="probe").refusal_evidence
    b = enforcer.guarded_invoke("block_session", source="probe").refusal_evidence
    assert a is not None and b is not None
    assert a.auditRecordId != b.auditRecordId
