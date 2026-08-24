"""Investigation_Graph node implementations for the Alert Triage Sidecar.

Each node is a function that takes the :class:`~sidecar.models.TriageState`
(plus any injected collaborators such as the read-only tool registry or the
language model) and returns either the updated state or a small routing
result/literal. Keeping I/O behind injectable interfaces (a
``ReadOnlyEngineBackend`` for tools, a ``HypothesisModel``/``ProbeStrategy`` for
the LLM/probe) lets the graph and its tests run deterministically without
external dependencies.

Nodes (populated incrementally by tasks 9-17):

  * ``intake_validate``  (task 9.1)  - validate the envelope before any Read_Tool
    runs; on success record it and route to ``gather_context`` (R4).
  * ``gather_context``   (task 10.1) - gather session history and actor profile
    into state, then route to ``correlate`` (R5).
  * ``correlate``        (task 11.1) - correlate related alerts and exfiltration
    signals, then route to ``form_hypotheses`` (R6).
  * ``form_hypotheses``  (task 12.1) - form 1-10 threat-mapped hypotheses from the
    language model, each backed by >=1 record-id-bound Evidence_Item (R7).
  * ``probe``            (task 13.1) - the bounded Probe_Loop, then route to
    ``synthesize_verdict`` (R8).
  * ``emit_report``      (task 17.1) - the terminal node; assemble exactly one
    ``Triage_Report`` (traceable evidence, exclusions, no-evidence flag, drafted
    action, schema version) and route to the graph end (R10).
"""

from sidecar.triage.nodes.intake import (
    INTAKE_FAILURE_THREAT_CATEGORY,
    INTAKE_STAGE,
    ROUTE_ESCALATE,
    ROUTE_GATHER_CONTEXT,
    EnvelopeInput,
    IntakeResult,
    intake_validate,
    route_after_intake,
)
from sidecar.triage.nodes.gather_context import (
    MISSING_ACTOR_ID_REASON,
    NODE_GATHER_CONTEXT,
    ROUTE_CORRELATE,
    gather_context,
)
from sidecar.triage.nodes.correlate import (
    CORRELATE_NODE,
    FORM_HYPOTHESES_NODE,
    correlate,
    route_after_correlate,
)
from sidecar.triage.nodes.form_hypotheses import (
    MAX_HYPOTHESES,
    ROUTE_PROBE,
    ROUTE_SYNTHESIZE_VERDICT,
    STAGE_HYPOTHESES,
    FormHypothesesResult,
    HypothesisModel,
    HypothesisRequest,
    StaticHypothesisModel,
    form_hypotheses,
)
from sidecar.triage.nodes.probe import (
    PROBE_NODE,
    PROBE_SOURCE,
    PROBE_STAGE,
    ProbeCall,
    ProbeResult,
    ProbeStopReason,
    ProbeStrategy,
    ScriptedProbeStrategy,
    probe,
    route_after_probe,
    wall_clock_ms,
)
from sidecar.triage.nodes.draft_action import (
    DRAFT_ACTION_NODE,
    ROUTE_EMIT_REPORT,
    ActionDraft,
    ActionDrafter,
    DefaultActionDrafter,
    DraftActionResult,
    draft_action,
    route_after_draft_action,
)
from sidecar.triage.nodes.escalate import (
    ESCALATE_NODE,
    EscalateResult,
    EscalationHook,
    escalate,
    route_after_escalate,
)
from sidecar.triage.nodes.synthesize_verdict import (
    MALFORMED_FALLBACK_THREAT_CATEGORY,
    NODE_SYNTHESIZE_VERDICT,
    ROUTE_DRAFT_ACTION,
    STAGE_VERDICT,
    StaticVerdictModel,
    SynthesizeVerdictResult,
    VerdictModel,
    VerdictRequest,
    route_after_synthesize_verdict,
    synthesize_verdict,
)
from sidecar.triage.nodes.emit_report import (
    DEFAULT_UNCERTAIN_THREAT_CATEGORY,
    EMIT_REPORT_NODE,
    REPORT_STAGE,
    ROUTE_END,
    EmitReportResult,
    emit_report,
)

__all__ = [
    # intake_validate node (task 9.1)
    "intake_validate",
    "route_after_intake",
    "IntakeResult",
    "EnvelopeInput",
    "ROUTE_GATHER_CONTEXT",
    "ROUTE_ESCALATE",
    "INTAKE_STAGE",
    "INTAKE_FAILURE_THREAT_CATEGORY",
    # gather_context node (task 10.1)
    "gather_context",
    "NODE_GATHER_CONTEXT",
    "ROUTE_CORRELATE",
    "MISSING_ACTOR_ID_REASON",
    # correlate node (task 11.1)
    "correlate",
    "route_after_correlate",
    "CORRELATE_NODE",
    "FORM_HYPOTHESES_NODE",
    # form_hypotheses node (task 12.1)
    "form_hypotheses",
    "FormHypothesesResult",
    "HypothesisModel",
    "HypothesisRequest",
    "StaticHypothesisModel",
    "MAX_HYPOTHESES",
    "STAGE_HYPOTHESES",
    "ROUTE_PROBE",
    "ROUTE_SYNTHESIZE_VERDICT",
    # probe node (task 13.1)
    "probe",
    "route_after_probe",
    "ProbeCall",
    "ProbeResult",
    "ProbeStopReason",
    "ProbeStrategy",
    "ScriptedProbeStrategy",
    "PROBE_NODE",
    "PROBE_SOURCE",
    "PROBE_STAGE",
    "wall_clock_ms",
    # synthesize_verdict node (task 14.1)
    "synthesize_verdict",
    "route_after_synthesize_verdict",
    "SynthesizeVerdictResult",
    "VerdictModel",
    "VerdictRequest",
    "StaticVerdictModel",
    "NODE_SYNTHESIZE_VERDICT",
    "ROUTE_DRAFT_ACTION",
    "STAGE_VERDICT",
    "MALFORMED_FALLBACK_THREAT_CATEGORY",
    # draft_action node (task 16.1)
    "draft_action",
    "route_after_draft_action",
    "DraftActionResult",
    "ActionDraft",
    "ActionDrafter",
    "DefaultActionDrafter",
    "DRAFT_ACTION_NODE",
    "ROUTE_EMIT_REPORT",
    # escalate node (task 16.1)
    "escalate",
    "route_after_escalate",
    "EscalateResult",
    "EscalationHook",
    "ESCALATE_NODE",
    # emit_report node (task 17.1)
    "emit_report",
    "EmitReportResult",
    "EMIT_REPORT_NODE",
    "ROUTE_END",
    "REPORT_STAGE",
    "DEFAULT_UNCERTAIN_THREAT_CATEGORY",
]
