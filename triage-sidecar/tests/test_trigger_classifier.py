"""Unit tests for the trigger classifier (task 4.1, R2.1-R2.6).

Covers the three admission outcomes and their edge cases:
  * ADMIT   - each of the four trigger conditions with a non-empty alertId (R2.1-R2.4).
  * DISCARD - a valid message carrying none of the four conditions (R2.5).
  * REJECT  - malformed alerts missing a required trigger field or alertId (R2.6),
              including that the rejection is recorded and names the field.

Property-based coverage lives in tasks 4.2-4.4; these are example/edge-case
unit tests that complement them.
"""

from __future__ import annotations

import pytest

from sidecar.models import TriggerType
from sidecar.triage import (
    ClassificationResult,
    TriageDecision,
    TriggerClassifier,
    classify_event,
)
from sidecar.triage.trigger import FIELD_ALERT_ID, FIELD_ENVELOPE, FIELD_TRIGGER_TYPE


def _event(trigger, alert_id="alert-123", **extra):
    """Build a minimal raw event dict."""
    ev = {"alertId": alert_id, "triggerType": trigger}
    ev.update(extra)
    return ev


# --- ADMIT (R2.1-R2.4) ----------------------------------------------------
@pytest.mark.parametrize("trigger", list(TriggerType))
def test_admits_each_trigger_condition_with_alert_id(trigger):
    clf = TriggerClassifier()
    result = clf.classify(_event(trigger.value))

    assert result.decision is TriageDecision.ADMIT
    assert result.admitted is True
    assert result.triggerType is trigger
    assert result.alertId == "alert-123"
    assert result.rejection is None
    # Admission is not a rejection; nothing recorded.
    assert clf.rejections == ()


def test_admits_when_trigger_is_already_an_enum_instance():
    result = classify_event(_event(TriggerType.CANARY_TOKEN))
    assert result.decision is TriageDecision.ADMIT
    assert result.triggerType is TriggerType.CANARY_TOKEN


def test_admit_strips_whitespace_around_alert_id():
    result = classify_event(_event(TriggerType.SESSION_HIJACK.value, alert_id="  a-9  "))
    assert result.decision is TriageDecision.ADMIT
    assert result.alertId == "a-9"


# --- DISCARD (R2.5) -------------------------------------------------------
def test_discards_event_with_non_trigger_type_value():
    clf = TriggerClassifier()
    result = clf.classify(_event("SOME_OTHER_EVENT"))

    assert result.decision is TriageDecision.DISCARD
    assert result.discarded is True
    assert result.triggerType is None
    assert result.alertId is None
    # Discards are not recorded (only rejections are, R2.6).
    assert clf.rejections == ()


def test_discards_even_when_alert_id_missing_because_not_a_trigger():
    # A non-triage message is dropped regardless of alertId (R2.5 intent).
    result = classify_event({"triggerType": "HEARTBEAT"})
    assert result.decision is TriageDecision.DISCARD


# --- REJECT: missing/invalid alertId (R2.6) -------------------------------
@pytest.mark.parametrize("bad_alert_id", [None, "", "   ", 123, 4.5, [], {}])
def test_rejects_valid_trigger_with_missing_or_invalid_alert_id(bad_alert_id):
    clf = TriggerClassifier()
    event = {"triggerType": TriggerType.BLOCK_RANGE_DIVERGENCE.value, "alertId": bad_alert_id}
    result = clf.classify(event)

    assert result.decision is TriageDecision.REJECT
    assert result.rejected is True
    assert result.rejection is not None
    assert result.rejection.missingField == FIELD_ALERT_ID
    assert result.rejection.reason  # non-empty error message
    # Rejection is recorded (R2.6).
    assert clf.rejections == (result.rejection,)


def test_rejects_when_alert_id_key_absent():
    result = classify_event({"triggerType": TriggerType.MONITORING_GAP.value})
    assert result.decision is TriageDecision.REJECT
    assert result.rejection.missingField == FIELD_ALERT_ID


# --- REJECT: missing trigger field (R2.6) ---------------------------------
def test_rejects_when_trigger_type_key_absent():
    clf = TriggerClassifier()
    result = clf.classify({"alertId": "alert-77"})

    assert result.decision is TriageDecision.REJECT
    assert result.rejection.missingField == FIELD_TRIGGER_TYPE
    # alertId was readable, so it is preserved on the rejection for correlation.
    assert result.rejection.alertId == "alert-77"
    assert clf.rejections == (result.rejection,)


def test_rejects_when_trigger_type_is_none():
    result = classify_event({"alertId": "alert-77", "triggerType": None})
    assert result.decision is TriageDecision.REJECT
    assert result.rejection.missingField == FIELD_TRIGGER_TYPE


# --- REJECT: non-mapping event --------------------------------------------
@pytest.mark.parametrize("bad_event", [None, "a string", 42, ["list"], object()])
def test_rejects_non_mapping_event(bad_event):
    clf = TriggerClassifier()
    result = clf.classify(bad_event)

    assert result.decision is TriageDecision.REJECT
    assert result.rejection.missingField == FIELD_ENVELOPE
    assert clf.rejections == (result.rejection,)


# --- Ledger accumulation (R2.6) -------------------------------------------
def test_classifier_accumulates_all_rejections_only():
    clf = TriggerClassifier()
    clf.classify({"alertId": "a"})                                  # reject (no trigger)
    clf.classify(_event(TriggerType.CANARY_TOKEN.value))            # admit
    clf.classify(_event("NOISE"))                                   # discard
    clf.classify({"triggerType": TriggerType.SESSION_HIJACK.value}) # reject (no alertId)

    assert len(clf.rejections) == 2
    assert [r.missingField for r in clf.rejections] == [FIELD_TRIGGER_TYPE, FIELD_ALERT_ID]


def test_result_is_frozen():
    result = classify_event(_event(TriggerType.CANARY_TOKEN.value))
    with pytest.raises(Exception):
        result.decision = TriageDecision.DISCARD  # type: ignore[misc]
    assert isinstance(result, ClassificationResult)
