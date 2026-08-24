"""Investigation_Graph nodes and orchestration for the Alert Triage Sidecar.

Populated by tasks 9-20 (graph nodes and end-to-end wiring).

Task 8.1 adds the reusable evidence binding/exclusion layer used by
``gather_context`` (10.1), ``correlate`` (11.1), ``form_hypotheses`` (12.1),
``probe`` (13.1), and report assembly (17.1):

  * ``RawElement``     - a pre-validation element with an optional record id.
  * ``EvidenceBinding`` - the partition of bound evidence / gaps / exclusions.
  * ``ElementBinding``  - the outcome of binding a single raw element.
  * ``bind_evidence``   - partition raw elements into bound + excluded.
  * ``bind_element``    - bind a single raw element.
  * ``is_bindable``     - whether an element carries a resolvable record id.

Task 9.1 adds the first graph node, re-exported here for convenience (its home
is the ``sidecar.triage.nodes`` subpackage):

  * ``intake_validate``    - validate the envelope before any Read_Tool runs.
  * ``route_after_intake`` - re-derive the intake routing decision from state.
  * ``IntakeResult``       - the node's ``(state, next)`` result.
  * ``ROUTE_GATHER_CONTEXT`` / ``ROUTE_ESCALATE`` - intake routing literals.
"""

from sidecar.triage.evidence import (
    UNBOUND_RECORD_ID_REASON,
    ElementBinding,
    EvidenceBinding,
    RawElement,
    bind_element,
    bind_evidence,
    is_bindable,
)
from sidecar.triage.idempotency import (
    AdmissionOutcome,
    AdmissionResult,
    Clock,
    IdempotencyStore,
    RunRecord,
    RunState,
)
from sidecar.triage.nodes import (
    CORRELATE_NODE,
    FORM_HYPOTHESES_NODE,
    correlate,
    route_after_correlate,
)
from sidecar.triage.nodes import (
    MISSING_ACTOR_ID_REASON,
    NODE_GATHER_CONTEXT,
    ROUTE_CORRELATE,
    gather_context,
)
from sidecar.triage.nodes import (
    INTAKE_FAILURE_THREAT_CATEGORY,
    INTAKE_STAGE,
    ROUTE_ESCALATE,
    ROUTE_GATHER_CONTEXT,
    IntakeResult,
    intake_validate,
    route_after_intake,
)
from sidecar.triage.nodes import (
    PROBE_NODE,
    PROBE_SOURCE,
    PROBE_STAGE,
    ROUTE_SYNTHESIZE_VERDICT,
    ProbeCall,
    ProbeResult,
    ProbeStopReason,
    ProbeStrategy,
    ScriptedProbeStrategy,
    probe,
    route_after_probe,
    wall_clock_ms,
)
from sidecar.triage.nodes import (
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
from sidecar.triage.trigger import (
    ClassificationResult,
    RejectionRecord,
    TriageDecision,
    TriggerClassifier,
    classify_event,
)
# ``form_hypotheses`` (task 12.1) is developed in parallel; re-export it when
# available without breaking this package while that module is still landing.
try:  # pragma: no cover - guarded against in-progress parallel work
    from sidecar.triage.nodes import (  # noqa: F401
        ROUTE_PROBE,
        FormHypothesesResult,
        HypothesisModel,
        HypothesisRequest,
        StaticHypothesisModel,
        form_hypotheses,
    )

    _FORM_HYPOTHESES_EXPORTS = [
        "form_hypotheses",
        "FormHypothesesResult",
        "HypothesisModel",
        "HypothesisRequest",
        "StaticHypothesisModel",
        "ROUTE_PROBE",
    ]
except ImportError:
    _FORM_HYPOTHESES_EXPORTS = []

__all__ = [
    # evidence binding (task 8.1)
    "UNBOUND_RECORD_ID_REASON",
    "RawElement",
    "ElementBinding",
    "EvidenceBinding",
    "is_bindable",
    "bind_element",
    "bind_evidence",
    # trigger classifier (task 4.1)
    "TriageDecision",
    "RejectionRecord",
    "ClassificationResult",
    "TriggerClassifier",
    "classify_event",
    # idempotency store (task 5.1)
    "AdmissionOutcome",
    "AdmissionResult",
    "Clock",
    "IdempotencyStore",
    "RunRecord",
    "RunState",
    # intake_validate node (task 9.1)
    "intake_validate",
    "route_after_intake",
    "IntakeResult",
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
    # probe node (task 13.1)
    "probe",
    "route_after_probe",
    "ProbeCall",
    "ProbeStrategy",
    "ScriptedProbeStrategy",
    "ProbeResult",
    "ProbeStopReason",
    "wall_clock_ms",
    "PROBE_NODE",
    "ROUTE_SYNTHESIZE_VERDICT",
    "PROBE_STAGE",
    "PROBE_SOURCE",
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
] + _FORM_HYPOTHESES_EXPORTS

# Investigation_Graph assembly + driver (task 20.1). Imported after the node
# re-exports above so the graph module's dependencies are already available.
from sidecar.triage.graph import (  # noqa: E402
    GRAPH_END,
    GUARD_FALLBACK_THREAT_CATEGORY,
    NODE_CORRELATE,
    NODE_DRAFT_ACTION,
    NODE_EMIT_REPORT,
    NODE_ESCALATE,
    NODE_FORM_HYPOTHESES,
    NODE_GATHER_CONTEXT,
    NODE_INTAKE_VALIDATE,
    NODE_PROBE,
    NODE_SYNTHESIZE_VERDICT,
    STAGE_BUDGET,
    STAGE_FAILURE,
    GraphCollaborators,
    InvestigationGraph,
    InvestigationOutcome,
    build_investigation_graph,
    run_investigation,
)

__all__ += [
    # Investigation_Graph assembly (task 20.1)
    "GraphCollaborators",
    "InvestigationOutcome",
    "InvestigationGraph",
    "build_investigation_graph",
    "run_investigation",
    "NODE_INTAKE_VALIDATE",
    "NODE_GATHER_CONTEXT",
    "NODE_CORRELATE",
    "NODE_FORM_HYPOTHESES",
    "NODE_PROBE",
    "NODE_SYNTHESIZE_VERDICT",
    "NODE_DRAFT_ACTION",
    "NODE_ESCALATE",
    "NODE_EMIT_REPORT",
    "GRAPH_END",
    "STAGE_BUDGET",
    "STAGE_FAILURE",
    "GUARD_FALLBACK_THREAT_CATEGORY",
]
