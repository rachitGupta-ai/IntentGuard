"""Unit tests for task 2.1: enums and leaf models."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from sidecar.models import (
    DeniedInvocation,
    ExclusionEntry,
    Gap,
    ThreatCategory,
    TriggerType,
    VerdictValue,
)


# --- Enums ----------------------------------------------------------------


def test_trigger_type_values():
    assert {t.value for t in TriggerType} == {
        "BLOCK_RANGE_DIVERGENCE",
        "SESSION_HIJACK",
        "MONITORING_GAP",
        "CANARY_TOKEN",
    }


def test_threat_category_values():
    assert {c.value for c in ThreatCategory} == {
        "prompt-injection",
        "session-hijack",
        "off-intent-agent",
        "benign-anomaly",
        "false-positive",
    }


def test_verdict_value_values():
    assert {v.value for v in VerdictValue} == {
        "confirmed_threat",
        "benign",
        "false_positive",
        "uncertain",
    }


def test_enums_are_str_backed():
    assert TriggerType.CANARY_TOKEN == "CANARY_TOKEN"
    assert ThreatCategory.PROMPT_INJECTION == "prompt-injection"
    assert VerdictValue.UNCERTAIN == "uncertain"


@pytest.mark.parametrize(
    "enum_cls,raw",
    [
        (TriggerType, "MONITORING_GAP"),
        (ThreatCategory, "off-intent-agent"),
        (VerdictValue, "confirmed_threat"),
    ],
)
def test_enums_construct_from_string(enum_cls, raw):
    assert enum_cls(raw).value == raw


@pytest.mark.parametrize(
    "enum_cls,bad",
    [
        (TriggerType, "block_range_divergence"),
        (ThreatCategory, "prompt_injection"),
        (VerdictValue, "confirmed threat"),
    ],
)
def test_enums_reject_unknown_values(enum_cls, bad):
    with pytest.raises(ValueError):
        enum_cls(bad)


# --- Gap ------------------------------------------------------------------


def test_gap_valid():
    gap = Gap(stage="context", element="get_session_history", reason="timeout")
    assert gap.stage == "context"
    assert gap.element == "get_session_history"
    assert gap.reason == "timeout"


def test_gap_is_frozen():
    gap = Gap(stage="probe", element="x", reason="no data")
    with pytest.raises(ValidationError):
        gap.reason = "changed"  # type: ignore[misc]


@pytest.mark.parametrize("field", ["stage", "element", "reason"])
def test_gap_rejects_empty_field(field):
    kwargs = {"stage": "context", "element": "e", "reason": "r"}
    kwargs[field] = ""
    with pytest.raises(ValidationError):
        Gap(**kwargs)


def test_gap_forbids_extra_fields():
    with pytest.raises(ValidationError):
        Gap(stage="context", element="e", reason="r", extra="nope")


# --- DeniedInvocation -----------------------------------------------------


def test_denied_invocation_valid():
    denied = DeniedInvocation(
        requestedTool="delete_session",
        source="untrusted_content",
        reason="outside read-only Read_Tool set",
    )
    assert denied.requestedTool == "delete_session"
    assert denied.source == "untrusted_content"


def test_denied_invocation_is_frozen():
    denied = DeniedInvocation(requestedTool="t", source="s", reason="r")
    with pytest.raises(ValidationError):
        denied.requestedTool = "other"  # type: ignore[misc]


@pytest.mark.parametrize("field", ["requestedTool", "source", "reason"])
def test_denied_invocation_rejects_empty_field(field):
    kwargs = {"requestedTool": "t", "source": "s", "reason": "r"}
    kwargs[field] = ""
    with pytest.raises(ValidationError):
        DeniedInvocation(**kwargs)


# --- ExclusionEntry -------------------------------------------------------


def test_exclusion_entry_valid():
    entry = ExclusionEntry(
        excludedItem="evidence-42",
        reason="no Audit_History record id",
    )
    assert entry.excludedItem == "evidence-42"
    assert entry.reason == "no Audit_History record id"


def test_exclusion_entry_is_frozen():
    entry = ExclusionEntry(excludedItem="i", reason="r")
    with pytest.raises(ValidationError):
        entry.reason = "changed"  # type: ignore[misc]


@pytest.mark.parametrize("field", ["excludedItem", "reason"])
def test_exclusion_entry_rejects_empty_field(field):
    kwargs = {"excludedItem": "i", "reason": "r"}
    kwargs[field] = ""
    with pytest.raises(ValidationError):
        ExclusionEntry(**kwargs)
