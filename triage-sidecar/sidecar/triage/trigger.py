"""Trigger classifier for the Alert Triage Sidecar (R2.1-R2.6).

The trigger classifier is the admission gate in front of the
``Investigation_Graph``. It takes a **raw incoming event** (a mapping / dict or
partially-parsed structure, possibly already carrying a ``TriggerType`` enum)
and decides one of three things:

  * **ADMIT**  - the event carries at least one of the four ``Triage_Trigger``
    conditions *and* a non-empty ``alertId``; a single Investigation_Graph run
    should be started, keyed by that ``alertId`` (R2.1-R2.4).
  * **DISCARD** - the event is well-formed enough to evaluate but carries **none**
    of the four trigger conditions; it is silently dropped and no run starts
    (R2.5).
  * **REJECT** - the event is **malformed**: it is missing a required trigger
    field or lacks a non-empty ``alertId``. The classifier returns an error that
    names the missing/invalid field and **records the rejection** (R2.6).

Discard vs reject
-----------------
The spec draws a deliberate line between the two "no run" outcomes:

  * R2.5 (*discard*) applies to a valid message that simply is not a triage
    trigger - the ``triggerType`` **field is present** but its value is not one
    of the four conditions. Such traffic is dropped without ceremony so
    investigation effort and LLM cost are not spent on it.
  * R2.6 (*reject*) applies to a **malformed** alert - the required
    ``triggerType`` field is **absent** (missing required trigger field), or the
    ``alertId`` is missing / empty / not a string. A reject returns an error
    naming the field and is recorded for audit.

Precedence is therefore unambiguous and the three outcomes are disjoint:

  1. ``triggerType`` present **and** a recognized trigger  -> validate ``alertId``:
       - non-empty ``alertId``  -> **ADMIT**
       - missing/empty ``alertId`` -> **REJECT** (field ``alertId``)
  2. ``triggerType`` present **but not** one of the four   -> **DISCARD** (R2.5)
  3. ``triggerType`` absent / ``None``                     -> **REJECT** (field
     ``triggerType`` - missing required trigger field, R2.6)

Deeper, trigger-specific payload validation (e.g. required ``signalPayload``
fields) is intentionally *not* performed here; it happens at the intake node
against the versioned Integration_Contract schema (R4.1, task 9). This gate is
concerned only with the trigger admission decision of R2.

Public API
----------
- ``TriageDecision``      : enum ``{ADMIT, DISCARD, REJECT}``.
- ``RejectionRecord``     : an immutable record of a rejected event (R2.6).
- ``ClassificationResult``: the classifier's decision plus its payload
  (``triggerType`` + ``alertId`` on ADMIT, ``rejection`` on REJECT).
- ``TriggerClassifier``   : the classifier; records every rejection in an
  auditable ledger (:pyattr:`TriggerClassifier.rejections`).
- ``classify_event``      : a convenience one-shot classification (stateless;
  does not record).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping, Optional

from sidecar.models import TriggerType

# Field names used in error/rejection reporting, kept as constants so callers
# and tests refer to the same identifiers.
FIELD_ALERT_ID = "alertId"
FIELD_TRIGGER_TYPE = "triggerType"
FIELD_ENVELOPE = "event"


class TriageDecision(str, Enum):
    """The admission decision for a single incoming event (R2.1-R2.6)."""

    ADMIT = "ADMIT"      # >=1 trigger condition + non-empty alertId (R2.1-R2.4)
    DISCARD = "DISCARD"  # none of the four trigger conditions (R2.5)
    REJECT = "REJECT"    # malformed: missing trigger field / alertId (R2.6)


@dataclass(frozen=True)
class RejectionRecord:
    """An immutable record of a malformed, rejected event (R2.6).

    Every rejection names the offending field (``missingField``) and carries a
    human-readable ``reason``. ``alertId`` is included when it could be read
    from the raw event (so a rejection for a missing trigger field can still be
    correlated), and is ``None`` when the ``alertId`` itself was missing/invalid.
    """

    missingField: str
    reason: str
    alertId: Optional[str] = None


@dataclass(frozen=True)
class ClassificationResult:
    """The outcome of classifying one incoming event.

    * On :attr:`TriageDecision.ADMIT`: ``triggerType`` and ``alertId`` are set
      and the caller should start exactly one Investigation_Graph run keyed by
      ``alertId`` (R2.1-R2.4).
    * On :attr:`TriageDecision.DISCARD`: all payload fields are ``None``; the
      event is dropped (R2.5).
    * On :attr:`TriageDecision.REJECT`: ``rejection`` is set with the offending
      field and reason (R2.6).
    """

    decision: TriageDecision
    triggerType: Optional[TriggerType] = None
    alertId: Optional[str] = None
    rejection: Optional[RejectionRecord] = None

    @property
    def admitted(self) -> bool:
        """True iff the event was admitted and a run should be started."""
        return self.decision is TriageDecision.ADMIT

    @property
    def discarded(self) -> bool:
        """True iff the event carried none of the four triggers and was dropped."""
        return self.decision is TriageDecision.DISCARD

    @property
    def rejected(self) -> bool:
        """True iff the event was malformed and rejected."""
        return self.decision is TriageDecision.REJECT


# Sentinel distinguishing the three states of the ``triggerType`` field.
class _TriggerFieldState(Enum):
    VALID = "valid"      # present and one of the four TriggerType values
    INVALID = "invalid"  # present but not a recognized trigger condition
    ABSENT = "absent"    # key missing or value is None


def _coerce_trigger(raw: Any) -> tuple[_TriggerFieldState, Optional[TriggerType]]:
    """Classify the raw ``triggerType`` value into one of three states.

    Accepts an already-parsed :class:`TriggerType`, its string value/name, or an
    arbitrary value. Returns the field state and, when valid, the resolved
    :class:`TriggerType`.
    """
    if raw is None:
        return _TriggerFieldState.ABSENT, None
    if isinstance(raw, TriggerType):
        return _TriggerFieldState.VALID, raw
    # Accept the enum's string value (e.g. "BLOCK_RANGE_DIVERGENCE"). The enum's
    # members use identical name/value, so value-lookup is sufficient.
    if isinstance(raw, str):
        try:
            return _TriggerFieldState.VALID, TriggerType(raw)
        except ValueError:
            return _TriggerFieldState.INVALID, None
    return _TriggerFieldState.INVALID, None


def _read_alert_id(raw: Any) -> Optional[str]:
    """Return a cleaned non-empty ``alertId`` string, or ``None`` if invalid.

    A valid ``alertId`` is a string with at least one non-whitespace character
    (R2.6, R3.1). Non-strings, ``None``, empty, and whitespace-only values are
    all invalid.
    """
    if not isinstance(raw, str):
        return None
    cleaned = raw.strip()
    return cleaned if cleaned else None


class TriggerClassifier:
    """Admits, discards, or rejects incoming events per R2.1-R2.6.

    The classifier keeps an auditable ledger of every rejection it produces
    (R2.6 "record the rejection"). Discards are *not* recorded - R2.5 only
    requires the event be dropped. The classifier holds no other state and is
    safe to reuse across many events; guard the ledger externally if shared
    across threads.
    """

    def __init__(self) -> None:
        self._rejections: list[RejectionRecord] = []

    @property
    def rejections(self) -> tuple[RejectionRecord, ...]:
        """An immutable view of every rejection recorded so far (R2.6)."""
        return tuple(self._rejections)

    def classify(self, event: Any) -> ClassificationResult:
        """Classify a single raw incoming ``event`` into ADMIT/DISCARD/REJECT.

        :param event: a mapping (dict) or partially-parsed structure. A
            non-mapping input is malformed and rejected.
        :returns: a :class:`ClassificationResult`. When the decision is
            ``REJECT`` the rejection has also been appended to
            :pyattr:`rejections`.
        """
        # A non-mapping event cannot carry the required fields at all -> reject.
        if not isinstance(event, Mapping):
            return self._reject(
                missing_field=FIELD_ENVELOPE,
                reason="incoming event is not a mapping/object and carries no fields",
                alert_id=None,
            )

        trigger_state, trigger = _coerce_trigger(event.get(FIELD_TRIGGER_TYPE))
        alert_id = _read_alert_id(event.get(FIELD_ALERT_ID))

        if trigger_state is _TriggerFieldState.VALID:
            # It is a genuine triage trigger; the only remaining requirement is a
            # non-empty alertId (R2.1-R2.4 + R2.6).
            if alert_id is None:
                return self._reject(
                    missing_field=FIELD_ALERT_ID,
                    reason="alertId is missing, empty, or not a non-empty string",
                    alert_id=None,
                )
            return ClassificationResult(
                decision=TriageDecision.ADMIT,
                triggerType=trigger,
                alertId=alert_id,
            )

        if trigger_state is _TriggerFieldState.INVALID:
            # The triggerType field is present but names none of the four
            # conditions: a valid, non-triage message -> discard (R2.5).
            return ClassificationResult(decision=TriageDecision.DISCARD)

        # trigger_state is ABSENT: the required trigger field is missing (R2.6).
        return self._reject(
            missing_field=FIELD_TRIGGER_TYPE,
            reason="required trigger field 'triggerType' is missing",
            alert_id=alert_id,
        )

    def _reject(
        self,
        missing_field: str,
        reason: str,
        alert_id: Optional[str],
    ) -> ClassificationResult:
        """Build, record, and return a REJECT result (R2.6)."""
        record = RejectionRecord(
            missingField=missing_field,
            reason=reason,
            alertId=alert_id,
        )
        self._rejections.append(record)
        return ClassificationResult(decision=TriageDecision.REJECT, rejection=record)


def classify_event(event: Any) -> ClassificationResult:
    """One-shot classification convenience (stateless; does not record).

    Useful when the caller only needs the decision and manages its own audit
    trail. For the R2.6 "record the rejection" guarantee, use a
    :class:`TriggerClassifier` instance and inspect :pyattr:`rejections`.
    """
    return TriggerClassifier().classify(event)


__all__ = [
    "TriageDecision",
    "RejectionRecord",
    "ClassificationResult",
    "TriggerClassifier",
    "classify_event",
    "FIELD_ALERT_ID",
    "FIELD_TRIGGER_TYPE",
    "FIELD_ENVELOPE",
]
