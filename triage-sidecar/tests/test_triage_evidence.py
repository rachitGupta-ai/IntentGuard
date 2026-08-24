"""Unit tests for task 8.1: evidence binding and exclusion helpers."""

from __future__ import annotations

from sidecar.models import Evidence_Item, ExclusionEntry, Gap
from sidecar.triage.evidence import (
    UNBOUND_RECORD_ID_REASON,
    ElementBinding,
    EvidenceBinding,
    RawElement,
    bind_element,
    bind_evidence,
    is_bindable,
)


# --- is_bindable ----------------------------------------------------------


def test_is_bindable_true_for_non_empty_record_id():
    assert is_bindable(RawElement(kind="context", summary="s", auditRecordId="evt-1"))


def test_is_bindable_false_for_missing_record_id():
    assert not is_bindable(RawElement(kind="context", summary="s"))


def test_is_bindable_false_for_none_and_empty_and_whitespace():
    assert not is_bindable(RawElement(kind="context", summary="s", auditRecordId=None))
    assert not is_bindable(RawElement(kind="context", summary="s", auditRecordId=""))
    assert not is_bindable(RawElement(kind="context", summary="s", auditRecordId="   "))


# --- bind_element ---------------------------------------------------------


def test_bind_element_binds_when_record_id_present():
    raw = RawElement(kind="context", summary="session opened", auditRecordId="evt-9")
    outcome = bind_element(raw, stage="context")

    assert outcome.bound is True
    assert isinstance(outcome.evidence, Evidence_Item)
    assert outcome.evidence.auditRecordId == "evt-9"
    assert outcome.evidence.kind == "context"
    assert outcome.evidence.summary == "session opened"
    assert outcome.gap is None
    assert outcome.exclusion is None


def test_bind_element_preserves_untrusted_flag():
    raw = RawElement(
        kind="probe",
        summary="untrusted blob",
        auditRecordId="evt-1",
        sourceContentUntrusted=True,
    )
    outcome = bind_element(raw, stage="probe")
    assert outcome.evidence is not None
    assert outcome.evidence.sourceContentUntrusted is True


def test_bind_element_excludes_when_record_id_missing():
    raw = RawElement(kind="context", summary="dangling fact")
    outcome = bind_element(raw, stage="correlate")

    assert outcome.bound is False
    assert outcome.evidence is None
    # Gap names the element, the stage, and the reason.
    assert isinstance(outcome.gap, Gap)
    assert outcome.gap.stage == "correlate"
    assert outcome.gap.element == "dangling fact"
    assert outcome.gap.reason == UNBOUND_RECORD_ID_REASON
    # Exclusion entry names the element and the reason.
    assert isinstance(outcome.exclusion, ExclusionEntry)
    assert outcome.exclusion.excludedItem == "dangling fact"
    assert outcome.exclusion.reason == UNBOUND_RECORD_ID_REASON


def test_bind_element_uses_element_id_as_label_when_present():
    raw = RawElement(
        kind="context", summary="a summary", elementId="ELEM-42", auditRecordId=""
    )
    outcome = bind_element(raw, stage="context")
    assert outcome.gap is not None and outcome.gap.element == "ELEM-42"
    assert outcome.exclusion is not None and outcome.exclusion.excludedItem == "ELEM-42"


def test_bind_element_falls_back_to_kind_when_summary_empty():
    # summary empty and no elementId -> label falls back to kind so the
    # gap/exclusion entries still satisfy the non-empty constraint.
    raw = RawElement(kind="correlation", summary="")
    outcome = bind_element(raw, stage="correlate")
    assert outcome.gap is not None and outcome.gap.element == "correlation"
    assert outcome.exclusion is not None
    assert outcome.exclusion.excludedItem == "correlation"


def test_bind_element_excludes_record_id_present_but_element_malformed():
    # Record id present but summary empty -> Evidence_Item construction fails;
    # the element is excluded with a descriptive reason rather than admitted.
    raw = RawElement(kind="context", summary="", auditRecordId="evt-1")
    outcome = bind_element(raw, stage="context")
    assert outcome.bound is False
    assert outcome.gap is not None
    assert outcome.gap.reason.startswith("invalid Evidence_Item")
    assert outcome.exclusion is not None
    assert outcome.exclusion.reason.startswith("invalid Evidence_Item")


# --- bind_evidence (partition) -------------------------------------------


def test_bind_evidence_partitions_bound_and_excluded():
    raws = [
        RawElement(kind="context", summary="bound-1", auditRecordId="evt-1"),
        RawElement(kind="correlation", summary="unbound-1"),
        RawElement(kind="probe", summary="bound-2", auditRecordId="evt-2"),
        RawElement(kind="context", summary="unbound-2", auditRecordId="  "),
    ]
    result = bind_evidence(raws, stage="probe")

    assert isinstance(result, EvidenceBinding)
    assert [e.summary for e in result.bound] == ["bound-1", "bound-2"]
    assert all(isinstance(e, Evidence_Item) for e in result.bound)
    # Every bound item carries a non-empty record id (traceability, R10.3).
    assert all(e.auditRecordId for e in result.bound)

    assert [g.element for g in result.gaps] == ["unbound-1", "unbound-2"]
    assert [x.excludedItem for x in result.exclusions] == ["unbound-1", "unbound-2"]
    assert result.has_exclusions is True


def test_bind_evidence_all_bound_has_no_exclusions():
    raws = [
        RawElement(kind="context", summary="a", auditRecordId="evt-1"),
        RawElement(kind="context", summary="b", auditRecordId="evt-2"),
    ]
    result = bind_evidence(raws, stage="context")
    assert len(result.bound) == 2
    assert result.gaps == []
    assert result.exclusions == []
    assert result.has_exclusions is False


def test_bind_evidence_gap_and_exclusion_counts_match_excluded():
    raws = [
        RawElement(kind="context", summary="unbound-a"),
        RawElement(kind="context", summary="unbound-b"),
        RawElement(kind="context", summary="bound", auditRecordId="evt-1"),
    ]
    result = bind_evidence(raws, stage="hypotheses")
    # One gap and one exclusion per excluded element.
    assert len(result.gaps) == len(result.exclusions) == 2
    assert all(g.stage == "hypotheses" for g in result.gaps)


def test_bind_evidence_empty_input():
    result = bind_evidence([], stage="context")
    assert result.bound == []
    assert result.gaps == []
    assert result.exclusions == []
    assert result.has_exclusions is False
