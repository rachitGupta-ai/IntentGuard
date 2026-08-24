"""Evidence binding and exclusion utilities (task 8.1).

Every fact the Investigation_Graph wants to use must be traceable to an
``Audit_History`` record id (R5.4, R6.4, R7.7, R10.3). Any element that cannot
be bound to a record id must be excluded from verdict synthesis and from the
report, and the exclusion must be recorded as both a :class:`Gap` (in the
investigation state) and an :class:`ExclusionEntry` (in the report), each naming
the element and the reason (R5.5, R10.4).

This module provides the *general, reusable* binding/exclusion layer that
operates on **raw returned elements** — descriptors that carry an optional and
possibly-missing record id plus ``kind``/``summary`` — *before* they become
validated :class:`Evidence_Item` s. It partitions raw elements into:

  * **bound** :class:`Evidence_Item` s (record id present and non-empty), and
  * **excluded** elements, each yielding a :class:`Gap` and an
    :class:`ExclusionEntry` that name the element and why it could not be bound.

Downstream consumers
--------------------
The graph nodes call :func:`bind_evidence` with the raw elements a tool or the
model returned and the investigation ``stage`` the call belongs to:

  * ``gather_context``  (task 10.1) — ``stage="context"``
  * ``correlate``       (task 11.1) — ``stage="correlate"``
  * ``form_hypotheses`` (task 12.1) — ``stage="hypotheses"`` (hypothesis support)
  * ``probe``           (task 13.1) — ``stage="probe"``
  * report assembly     (task 17.1) — filters already-bound evidence / re-checks

Public API
----------
Input shape:
  * :class:`RawElement` — ``kind``, ``summary``, optional ``auditRecordId``,
    optional ``elementId`` (naming fallback), ``sourceContentUntrusted``.

Partition / return shape:
  * :class:`EvidenceBinding` — ``bound: list[Evidence_Item]``,
    ``gaps: list[Gap]``, ``exclusions: list[ExclusionEntry]``.

Helpers:
  * :func:`is_bindable(raw) -> bool`
  * :func:`bind_element(raw, *, stage) -> ElementBinding`
  * :func:`bind_evidence(raw_elements, *, stage) -> EvidenceBinding`
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Optional

from pydantic import ValidationError

from sidecar.models import Evidence_Item, ExclusionEntry, Gap

# Reason recorded when an element carries no resolvable Audit_History record id.
UNBOUND_RECORD_ID_REASON = "no Audit_History record id"

# Fallback label used to name an element that carries nothing identifying.
_UNIDENTIFIED_ELEMENT = "unidentified element"


@dataclass(frozen=True)
class RawElement:
    """A raw returned element awaiting binding to an ``Audit_History`` record id.

    This is the *pre-validation* shape: unlike :class:`Evidence_Item`, the
    ``auditRecordId`` here is optional and may be missing, ``None``, empty, or
    whitespace-only — exactly the cases that must be excluded (R5.5, R10.4).

    Attributes:
        kind: The evidence kind (``context`` | ``correlation`` | ``probe`` |
            ``injection_attempt`` | ``refusal`` | ``hypothesis-support`` ...).
        summary: Analysis-only description of the fact.
        auditRecordId: The originating record id, if the tool could tag one.
        elementId: Optional stable identifier used to name the element in a gap
            or exclusion entry; falls back to ``summary`` then ``kind``.
        sourceContentUntrusted: Whether the source content is untrusted (R12.1).
    """

    kind: str
    summary: str
    auditRecordId: Optional[str] = None
    elementId: Optional[str] = None
    sourceContentUntrusted: bool = True


@dataclass(frozen=True)
class ElementBinding:
    """The outcome of binding a single :class:`RawElement`.

    Exactly one of ``evidence`` (bound) or the ``gap``/``exclusion`` pair
    (excluded) is populated.
    """

    evidence: Optional[Evidence_Item] = None
    gap: Optional[Gap] = None
    exclusion: Optional[ExclusionEntry] = None

    @property
    def bound(self) -> bool:
        """True when the element was successfully bound to a record id."""

        return self.evidence is not None


@dataclass(frozen=True)
class EvidenceBinding:
    """The partition produced by :func:`bind_evidence`.

    Attributes:
        bound: Successfully bound :class:`Evidence_Item` s (each carries a
            non-empty ``auditRecordId``) — safe for verdict synthesis and the
            report (R10.3).
        gaps: One :class:`Gap` per excluded element, for the investigation state
            (R5.5).
        exclusions: One :class:`ExclusionEntry` per excluded element, for the
            report (R10.4).
    """

    bound: list[Evidence_Item] = field(default_factory=list)
    gaps: list[Gap] = field(default_factory=list)
    exclusions: list[ExclusionEntry] = field(default_factory=list)

    @property
    def has_exclusions(self) -> bool:
        """True when at least one element could not be bound."""

        return bool(self.exclusions)


def _record_id_present(audit_record_id: Optional[str]) -> bool:
    """A record id is present only if it is a non-empty, non-whitespace string."""

    return bool(audit_record_id) and bool(str(audit_record_id).strip())


def _element_label(raw: RawElement) -> str:
    """Pick a non-empty identifier to name ``raw`` in a gap/exclusion entry."""

    for candidate in (raw.elementId, raw.summary, raw.kind):
        if candidate and str(candidate).strip():
            return str(candidate)
    return _UNIDENTIFIED_ELEMENT


def is_bindable(raw: RawElement) -> bool:
    """Return whether ``raw`` carries a resolvable ``Audit_History`` record id."""

    return _record_id_present(raw.auditRecordId)


def bind_element(raw: RawElement, *, stage: str) -> ElementBinding:
    """Bind a single raw element, or produce a gap + exclusion if it cannot bind.

    An element binds when it carries a non-empty record id *and* the resulting
    :class:`Evidence_Item` validates. Otherwise it is excluded and a matching
    :class:`Gap` (``stage``/``element``/``reason``) and :class:`ExclusionEntry`
    (``excludedItem``/``reason``) are returned, both naming the element and the
    reason (R5.5, R10.4).

    Args:
        raw: The raw element to bind.
        stage: The investigation stage the element belongs to (recorded on the
            gap; e.g. ``context``, ``correlate``, ``hypotheses``, ``probe``).

    Returns:
        An :class:`ElementBinding`; ``.bound`` is True when the element was
        bound.
    """

    label = _element_label(raw)

    if not _record_id_present(raw.auditRecordId):
        return ElementBinding(
            gap=Gap(stage=stage, element=label, reason=UNBOUND_RECORD_ID_REASON),
            exclusion=ExclusionEntry(
                excludedItem=label, reason=UNBOUND_RECORD_ID_REASON
            ),
        )

    try:
        evidence = Evidence_Item(
            auditRecordId=str(raw.auditRecordId),
            kind=raw.kind,
            summary=raw.summary,
            sourceContentUntrusted=raw.sourceContentUntrusted,
        )
    except ValidationError as exc:
        # A record id was present but the element was otherwise malformed
        # (e.g. empty kind/summary). Exclude it and record why, rather than
        # letting a bad element into synthesis or the report.
        reason = f"invalid Evidence_Item: {_summarize_validation_error(exc)}"
        return ElementBinding(
            gap=Gap(stage=stage, element=label, reason=reason),
            exclusion=ExclusionEntry(excludedItem=label, reason=reason),
        )

    return ElementBinding(evidence=evidence)


def bind_evidence(
    raw_elements: Iterable[RawElement], *, stage: str
) -> EvidenceBinding:
    """Partition raw elements into bound evidence and excluded elements.

    This is the primary entry point for graph nodes. Each element that carries a
    non-empty ``Audit_History`` record id becomes a bound :class:`Evidence_Item`;
    each element that cannot be bound is excluded from synthesis/report and
    recorded as a :class:`Gap` and an :class:`ExclusionEntry` naming the element
    and the reason (R5.5, R10.4). Bound items are guaranteed traceable (R10.3).

    Args:
        raw_elements: The raw elements returned by a tool or the model.
        stage: The investigation stage recorded on any produced gaps.

    Returns:
        An :class:`EvidenceBinding` with ``bound``, ``gaps``, and ``exclusions``.
        Order within each list follows input order.
    """

    result = EvidenceBinding()
    for raw in raw_elements:
        outcome = bind_element(raw, stage=stage)
        if outcome.evidence is not None:
            result.bound.append(outcome.evidence)
        else:
            # By construction an unbound element yields both a gap and an
            # exclusion; append both.
            if outcome.gap is not None:
                result.gaps.append(outcome.gap)
            if outcome.exclusion is not None:
                result.exclusions.append(outcome.exclusion)
    return result


def _summarize_validation_error(exc: ValidationError) -> str:
    """Condense a pydantic error into a short, human-readable reason string."""

    parts = []
    for err in exc.errors():
        loc = ".".join(str(p) for p in err.get("loc", ())) or "value"
        parts.append(f"{loc}: {err.get('msg', 'invalid')}")
    return "; ".join(parts) if parts else "validation failed"


__all__ = [
    "UNBOUND_RECORD_ID_REASON",
    "RawElement",
    "ElementBinding",
    "EvidenceBinding",
    "is_bindable",
    "bind_element",
    "bind_evidence",
]
