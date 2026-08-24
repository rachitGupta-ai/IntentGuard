"""Composite models and graph state for the Alert Triage Sidecar.

These schemas assemble the enums (``sidecar.models.enums``) and leaf records
(``sidecar.models.leaf``) into the larger structures the investigation graph
produces and threads through its nodes:

  * ``AlertEnvelope``      - the validated intake payload for a triage run
                             (R2.6, R3.1, R4.1, R4.4, R6.3, R14.5).
  * ``Evidence_Item``      - a single supporting fact bound to an Audit_History
                             record id (R5, R6, R7, R10.3, R10.4, R12.1).
  * ``Hypothesis``         - a candidate explanation mapped to one Threat_Category
                             with >=1 supporting Evidence_Item (R7.4, R7.6, R7.8).
  * ``Verdict``            - the synthesized, schema-validated, clamped decision
                             (R9.1, R9.2, R9.4, R9.5).
  * ``Recommended_Action`` - a drafted, never-executed action (R1.4, R1.5, R1.6).
  * ``Triage_Report``      - the structured investigation output (R10).
  * ``TriageState``        - the mutable LangGraph state object (R2-R13).

Design choices worth flagging:

  * Records that are established once and then read (``AlertEnvelope``,
    ``Evidence_Item``, ``Verdict``, ``Recommended_Action``, ``Triage_Report``)
    are ``frozen`` - mirroring the leaf models - so a fact cannot be mutated
    after it is recorded.
  * ``Hypothesis`` is intentionally *not* frozen: its ``resolved`` flag is
    flipped in place by the probe loop as evidence resolves it (R7, R8).
  * ``TriageState`` is a mutable ``BaseModel`` with ``validate_assignment=True``
    because it is threaded through and updated by every graph node. Field
    assignments are re-validated so invariants (e.g. enum membership) hold on
    update, not just at construction.
  * ``Verdict.confidence`` and ``Triage_Report.confidence`` are *clamped* to
    ``[0.0, 1.0]`` (not rejected), matching R9.2's "clamp below 0.0 to 0.0 and
    above 1.0 to 1.0" and R9.5/R10.1's in-range guarantee.
  * ``Recommended_Action.executed`` is ``Literal[False]``: the type system
    itself forbids ever marking an action executed (R1.4, R1.6).
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

from sidecar.models.enums import ThreatCategory, TriggerType, VerdictValue
from sidecar.models.leaf import DeniedInvocation, ExclusionEntry, Gap


def _clamp_unit_interval(value: float) -> float:
    """Clamp a numeric confidence into the closed interval ``[0.0, 1.0]``.

    R9.2 requires clamping below-0.0 to 0.0 and above-1.0 to 1.0 rather than
    rejecting out-of-range confidences.
    """

    return min(1.0, max(0.0, float(value)))


class AlertEnvelope(BaseModel):
    """The validated intake payload that starts (or keys) a triage run.

    Produced from the incoming ``Triage_Trigger`` and validated at intake
    (R4.1). ``alertId`` is required and non-empty (R2.6, R3.1); ``triggerType``
    is one of the four qualifying conditions (R2.1-R2.4). ``actorId`` and
    ``sessionId`` may be absent for engine-wide triggers such as a monitoring
    gap.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    schemaVersion: str = Field(
        ...,
        min_length=1,
        description="Required; validated against the supported schema set (R14.5).",
    )
    alertId: str = Field(
        ...,
        min_length=1,
        description="Required, non-empty identifier for the triage run (R2.6, R3.1).",
    )
    triggerType: TriggerType = Field(
        ...,
        description="One of the four Triage_Trigger conditions (R2.1-R2.4).",
    )
    actorId: Optional[str] = Field(
        default=None,
        description="Actor identity; may be null for engine-wide triggers.",
    )
    sessionId: Optional[str] = Field(
        default=None,
        description="Session identity; may be null for engine-wide triggers.",
    )
    alertTimestampMs: int = Field(
        ...,
        description="Trigger timestamp (ms); anchors the correlation window (R6.3).",
    )
    divergenceScore: Optional[float] = Field(
        default=None,
        description="Present for BLOCK_RANGE_DIVERGENCE triggers.",
    )
    signalPayload: dict = Field(
        ...,
        description="Trigger-specific fields, validated at intake (R4.1).",
    )


class Evidence_Item(BaseModel):
    """A single supporting fact bound to an Audit_History record id.

    Every Evidence_Item carries the ``auditRecordId`` it derives from (R10.3).
    An item that cannot be bound to a record id is excluded from the report via
    an ``ExclusionEntry`` (R10.4). Prompt-injection attempts and out-of-scope
    refusals are themselves recorded as Evidence_Items (R12.3, R12.5).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    auditRecordId: str = Field(
        ...,
        min_length=1,
        description="REQUIRED: the Audit_History record id this fact derives from (R10.3).",
    )
    kind: str = Field(
        ...,
        min_length=1,
        description="context | correlation | probe | injection_attempt | refusal.",
    )
    summary: str = Field(
        ...,
        min_length=1,
        description="Analysis-only description of the fact.",
    )
    sourceContentUntrusted: bool = Field(
        default=True,
        description="Fetched content is Untrusted_Content by default (R12.1).",
    )


class Hypothesis(BaseModel):
    """A candidate explanation mapped to exactly one Threat_Category.

    A hypothesis requires at least one supporting Evidence_Item; one with none
    is discarded (R7.6, R7.8). ``resolved`` is flipped by the probe loop as
    evidence confirms or eliminates the hypothesis, so this model is mutable.
    """

    model_config = ConfigDict(extra="forbid", validate_assignment=True)

    id: str = Field(..., min_length=1, description="Stable hypothesis identifier.")
    statement: str = Field(
        ...,
        min_length=1,
        description="The candidate explanation being investigated.",
    )
    threatCategory: ThreatCategory = Field(
        ...,
        description="Exactly one of the five permitted Threat_Category labels (R7.4).",
    )
    supportingEvidence: list[Evidence_Item] = Field(
        ...,
        min_length=1,
        description=">= 1 Evidence_Item required, else the hypothesis is discarded (R7.6, R7.8).",
    )
    resolved: bool = Field(
        default=False,
        description="Set once the probe loop resolves the hypothesis (R7, R8).",
    )


class Verdict(BaseModel):
    """The synthesized, schema-validated, confidence-clamped decision (R9).

    ``value`` and ``threatCategory`` are constrained to their permitted sets by
    the enum types. ``confidence`` is clamped into ``[0.0, 1.0]`` (R9.2).
    ``malformedRejected`` records that fail-open-to-human handling fired (R9.4).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    value: VerdictValue = Field(
        ...,
        description="confirmed_threat | benign | false_positive | uncertain (R9.1).",
    )
    confidence: float = Field(
        ...,
        description="Clamped to [0.0, 1.0] (R9.2, R9.5).",
    )
    threatCategory: ThreatCategory = Field(
        ...,
        description="One of the five permitted Threat_Category labels (R9.2).",
    )
    malformedRejected: bool = Field(
        default=False,
        description="Set when malformed-output handling fired (R9.4).",
    )

    @field_validator("confidence")
    @classmethod
    def _clamp_confidence(cls, value: float) -> float:
        return _clamp_unit_interval(value)


class Recommended_Action(BaseModel):
    """A drafted action that the sidecar never executes (R1.4, R1.6).

    If ``targetsProtectedState`` is true the action must be routed to the
    Control_Tower for human approval and left unexecuted (R1.5). ``executed``
    is ``Literal[False]`` so the type system forbids ever recording it as
    executed.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    description: str = Field(
        ...,
        min_length=1,
        description="Human-readable description of the proposed action.",
    )
    targetsProtectedState: bool = Field(
        ...,
        description="If true -> route to Control_Tower, leave unexecuted (R1.5).",
    )
    executed: Literal[False] = Field(
        default=False,
        description="NEVER executed by the sidecar (R1.4, R1.6).",
    )


class Triage_Report(BaseModel):
    """The structured output of a completed investigation (R10).

    Carries the verdict, in-range confidence (R10.1), threat category, the bound
    Evidence_Items (each with an ``auditRecordId``, R10.3), any excluded items
    with reasons (R10.4), a no-evidence flag (R10.5), the optional drafted
    Recommended_Action (R10.2), and the outgoing schema version (R14.1).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    alertId: str = Field(
        ...,
        min_length=1,
        description="The triggering alertId (R10.6).",
    )
    triggerType: TriggerType = Field(..., description="The triggering condition (R10.6).")
    verdict: VerdictValue = Field(..., description="The synthesized verdict value.")
    confidence: float = Field(
        ...,
        description="Decimal in [0.0, 1.0] inclusive (R10.1).",
    )
    threatCategory: ThreatCategory = Field(
        ...,
        description="The assigned Threat_Category.",
    )
    evidence: list[Evidence_Item] = Field(
        ...,
        description="Bound Evidence_Items, each carrying an auditRecordId (R10.3).",
    )
    excludedEvidence: list[ExclusionEntry] = Field(
        default_factory=list,
        description="Unbindable items plus the reason each was excluded (R10.4).",
    )
    noEvidenceFlag: bool = Field(
        default=False,
        description="True when the evidence collection is empty (R10.5).",
    )
    recommendedAction: Optional[Recommended_Action] = Field(
        default=None,
        description="Included if a Recommended_Action was drafted (R10.2).",
    )
    schemaVersion: str = Field(
        ...,
        min_length=1,
        description="On every outgoing message (R14.1).",
    )

    @field_validator("confidence")
    @classmethod
    def _clamp_confidence(cls, value: float) -> float:
        return _clamp_unit_interval(value)


class TriageState(BaseModel):
    """The single mutable state object threaded through every graph node.

    Unlike the record models this is intentionally mutable and re-validates
    assignments (``validate_assignment=True``) because graph nodes append
    evidence, record gaps, advance probe counters, set the verdict, etc. Lists
    use ``default_factory`` so each state instance gets its own collections.
    """

    model_config = ConfigDict(extra="forbid", validate_assignment=True)

    alertId: str = Field(..., min_length=1, description="The alertId under investigation.")
    envelope: Optional[AlertEnvelope] = Field(
        default=None,
        description="The validated intake envelope (set after intake, R4.4).",
    )
    triggerType: Optional[TriggerType] = Field(
        default=None,
        description="The triggering condition.",
    )
    context: list[Evidence_Item] = Field(
        default_factory=list,
        description="Gathered session/profile context (R5).",
    )
    correlations: list[Evidence_Item] = Field(
        default_factory=list,
        description="Correlated signals (R6).",
    )
    hypotheses: list[Hypothesis] = Field(
        default_factory=list,
        description="1..10 hypotheses mapped to threat categories (R7).",
    )
    evidence: list[Evidence_Item] = Field(
        default_factory=list,
        description="All bound evidence collected.",
    )
    gaps: list[Gap] = Field(
        default_factory=list,
        description="Recorded gaps (R4.3, R5.3, R6.5, R7.x, R8.8).",
    )
    probe_steps_used: int = Field(
        default=0,
        ge=0,
        description="Probe steps consumed against the max step count (R8.2).",
    )
    probe_started_ms: Optional[int] = Field(
        default=None,
        description="Probe start time (ms) for the probe wall-clock budget (R8.3).",
    )
    investigation_started_ms: int = Field(
        ...,
        description="Investigation start time (ms) for the total 300s budget (R13.3).",
    )
    verdict: Optional[Verdict] = Field(
        default=None,
        description="The synthesized verdict once available (R9).",
    )
    recommended_action: Optional[Recommended_Action] = Field(
        default=None,
        description="The drafted action once available (R1.4).",
    )
    escalated: bool = Field(
        default=False,
        description="True once the run has escalated to a human (R9.4, R9.6, R11).",
    )
    denied_invocations: list[DeniedInvocation] = Field(
        default_factory=list,
        description="Out-of-scope tool requests refused pre-execution (R1.3, R8.9, R12.5).",
    )


__all__ = [
    "AlertEnvelope",
    "Evidence_Item",
    "Hypothesis",
    "Verdict",
    "Recommended_Action",
    "Triage_Report",
    "TriageState",
]
