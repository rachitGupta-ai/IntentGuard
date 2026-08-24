"""Leaf (record) models for the Alert Triage Sidecar.

These are the small, self-contained records that composite models
(``TriageState``, ``Triage_Report``) collect. They carry no references to other
sidecar models, which is why they live apart from the composite schemas built in
task 2.2.

  * ``Gap``             - a recorded shortfall in the investigation state
                          (R4.3, R5.3, R5.5, R6.5, R7.3, R7.5, R7.8, R7.9, R8.8).
  * ``DeniedInvocation`` - a record of a tool request refused before execution
                          because it fell outside the read-only Read_Tool set
                          (R1.3, R8.9, R12.5).
  * ``ExclusionEntry``  - a report entry naming an Evidence_Item excluded because
                          it could not be bound to an Audit_History record id,
                          plus the reason (R10.4).

All three are frozen: once recorded, a fact about an investigation does not
change. ``extra="forbid"`` keeps the wire schema tight.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class Gap(BaseModel):
    """A recorded shortfall encountered during an investigation.

    Gaps are how the graph "continues without halting" on missing data, tool
    errors, timeouts, unmappable hypotheses, or unbindable evidence. Every
    non-fatal shortfall is captured as a Gap so the resulting Triage_Report is
    honest about what could not be established.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    stage: str = Field(
        ...,
        min_length=1,
        description="Investigation stage where the gap arose "
        "(intake | context | correlate | hypotheses | probe | verdict).",
    )
    element: str = Field(
        ...,
        min_length=1,
        description="The element that was missing or failed.",
    )
    reason: str = Field(
        ...,
        min_length=1,
        description="Why the element is a gap (timeout, no data, error, "
        "unbound record id, unmappable category, ...).",
    )


class DeniedInvocation(BaseModel):
    """A tool request denied before execution for being out of scope.

    The read-only tool layer is the structural guarantee; any request for a
    tool outside the Read_Tool registry is refused pre-execution and recorded
    here so the denial is auditable (R1.3, R8.9, R12.5).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    requestedTool: str = Field(
        ...,
        min_length=1,
        description="The out-of-scope tool/action that was requested.",
    )
    source: str = Field(
        ...,
        min_length=1,
        description="Where the request originated (e.g. probe, untrusted content).",
    )
    reason: str = Field(
        ...,
        min_length=1,
        description="Why the invocation was denied (outside read-only Read_Tool set).",
    )


class ExclusionEntry(BaseModel):
    """A report entry for an Evidence_Item excluded from the Triage_Report.

    An Evidence_Item that cannot be associated with an Audit_History record id
    is excluded from the report's evidence collection; the report instead
    carries this entry identifying the excluded item and the reason (R10.4).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    excludedItem: str = Field(
        ...,
        min_length=1,
        description="Identifier or summary of the excluded Evidence_Item.",
    )
    reason: str = Field(
        ...,
        min_length=1,
        description="Why the item was excluded (e.g. no Audit_History record id).",
    )


__all__ = ["Gap", "DeniedInvocation", "ExclusionEntry"]
