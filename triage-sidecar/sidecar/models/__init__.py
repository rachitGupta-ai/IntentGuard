"""Pydantic data models for the Alert Triage Sidecar.

Public API (task 2.1 - enums and leaf models)
----------------------------------------------
Enums (closed value sets):
  * ``TriggerType``    : the four Triage_Trigger conditions (R2.1-R2.4).
  * ``ThreatCategory`` : the five permitted threat-classification labels (R7.4).
  * ``VerdictValue``   : the four permitted verdict values (R9.1).

Leaf records:
  * ``Gap``             : a recorded investigation shortfall.
  * ``DeniedInvocation``: a refused, out-of-scope tool request (R1.3, R8.9, R12.5).
  * ``ExclusionEntry``  : an Evidence_Item excluded from the report (R10.4).

Public API (task 2.2 - composite models and graph state)
---------------------------------------------------------
Composite records:
  * ``AlertEnvelope``      : the validated intake payload (R2.6, R3.1, R4, R14.5).
  * ``Evidence_Item``      : a fact bound to an Audit_History record id (R10.3).
  * ``Hypothesis``         : a candidate explanation mapped to a Threat_Category (R7).
  * ``Verdict``            : the synthesized, clamped decision (R9).
  * ``Recommended_Action`` : a drafted, never-executed action (R1.4, R1.6).
  * ``Triage_Report``      : the structured investigation output (R10).

Graph state:
  * ``TriageState``        : the mutable LangGraph state object.
"""

from sidecar.models.composite import (
    AlertEnvelope,
    Evidence_Item,
    Hypothesis,
    Recommended_Action,
    Triage_Report,
    TriageState,
    Verdict,
)
from sidecar.models.enums import ThreatCategory, TriggerType, VerdictValue
from sidecar.models.leaf import DeniedInvocation, ExclusionEntry, Gap

__all__ = [
    # enums
    "TriggerType",
    "ThreatCategory",
    "VerdictValue",
    # leaf models
    "Gap",
    "DeniedInvocation",
    "ExclusionEntry",
    # composite models
    "AlertEnvelope",
    "Evidence_Item",
    "Hypothesis",
    "Verdict",
    "Recommended_Action",
    "Triage_Report",
    # graph state
    "TriageState",
]
