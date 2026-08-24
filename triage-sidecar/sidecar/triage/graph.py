"""Investigation_Graph assembly and driver (task 20.1).

This module wires the nine investigation nodes (implemented in
:mod:`sidecar.triage.nodes`) into a single, runnable state machine that mirrors
the design's ``Investigation_Graph`` state diagram::

    intake_validate --> gather_context --> correlate --> form_hypotheses
    form_hypotheses --> probe | synthesize_verdict
    probe           --> synthesize_verdict
    synthesize_verdict --> draft_action | escalate
    draft_action    --> emit_report
    escalate        --> emit_report
    emit_report     --> [*]

and enforces the three cross-cutting guards the design requires on **every** node
transition.

Why a hand-rolled deterministic driver (not raw LangGraph edges)
----------------------------------------------------------------
Each node already returns a small result object exposing the updated
:class:`~sidecar.models.TriageState` **and a routing literal** whose value equals
the *name of the next node* (``IntakeResult.next``, ``FormHypothesesResult.route``,
``ProbeResult.next``, ``SynthesizeVerdictResult.route``, ``DraftActionResult.next``,
``EscalateResult.next``, ``EmitReportResult.next``), and the package also exposes
``route_after_*`` helpers that re-derive the same decision from state. That
``(state, route-literal)`` convention *is* a state machine already. A hand-rolled
driver consumes those literals directly, so:

  * the cross-cutting guards (total budget, unrecoverable-failure boundary,
    read-only enforcement) are expressed once, in one place, and are checked on
    every transition without adapting each node to a LangGraph signature; and
  * the whole graph runs synchronously and deterministically under injected
    doubles (``StaticEngineBackend``, ``StaticHypothesisModel``,
    ``StaticVerdictModel``, ``InlineTimeoutRunner``, ``ScriptedProbeStrategy``)
    with an **injectable clock**, so its termination and guard behaviour are
    directly unit-testable with no real time or engine I/O.

``langgraph`` remains available for a future swap, but the nodes' routing-literal
convention makes this equivalent driver the cleaner, more testable fit; the
node functions are untouched and could be registered as LangGraph nodes later
with the same edges.

Cross-cutting guards (checked on every transition)
--------------------------------------------------
* **Total investigation budget (R13.3).** Before dispatching each investigative
  node the driver checks ``clock() - state.investigation_started_ms`` against
  ``config.total_investigation_budget_seconds`` (300s). On overrun it forces an
  ``uncertain`` verdict (confidence ``0.0``), **retains all evidence gathered so
  far**, records a gap, and routes straight to ``escalate`` → ``emit_report``.
  ``clock`` is injectable so the budget is exercised deterministically.
* **Unrecoverable-failure boundary (R13.4).** The entire run is wrapped so that
  **any** uncaught exception from any node (or guard) is converted, at the graph
  boundary, into an ``uncertain`` verdict + ``escalate`` and *still emits a
  Triage_Report* — the run never crashes.
* **Read-only enforcement (R1.3/R8.9/R12.5).** Tool access flows through the
  injected :class:`~sidecar.tools.ReadToolRegistry` / :class:`~sidecar.tools.ReadOnlyEnforcer`,
  so any out-of-set request is denied before execution and recorded, at any node
  (the probe node routes every call through the enforcer; context/correlation
  nodes only ever call in-set registry methods).

Exactly one :class:`~sidecar.models.Triage_Report` is produced per run — the
terminal ``emit_report`` node is the only producer, and every path converges on
it.

Public API
----------
  * :class:`GraphCollaborators` — the injected collaborators bundle (registry /
    enforcer, hypothesis model, verdict model, probe strategy, clock, and
    optional config / drafter / timeout runner / stamper / schema version /
    escalation hook).
  * :class:`InvestigationGraph` — the assembled graph; call :meth:`InvestigationGraph.run`.
  * :class:`InvestigationOutcome` — the ``(state, report)`` result of a run.
  * :func:`build_investigation_graph` — build a graph from collaborators.
  * :func:`run_investigation` — top-level entry: run an investigation from a
    :class:`~sidecar.models.TriageState` or an alert envelope and return the
    :class:`InvestigationOutcome` (final state + the single report).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional, Union

from sidecar.config import SidecarConfig, get_config
from sidecar.contract import MessageStamper
from sidecar.models import (
    AlertEnvelope,
    Gap,
    ThreatCategory,
    Triage_Report,
    TriageState,
    Verdict,
    VerdictValue,
)
from sidecar.tools import ReadOnlyEnforcer, ReadToolRegistry
from sidecar.tools.timeout import TimeoutRunner
from sidecar.triage.nodes import (
    ActionDrafter,
    EscalationHook,
    HypothesisModel,
    ProbeStrategy,
    VerdictModel,
    correlate,
    draft_action,
    emit_report,
    escalate,
    form_hypotheses,
    gather_context,
    intake_validate,
    probe,
    synthesize_verdict,
)
from sidecar.triage.nodes.probe import Clock, wall_clock_ms

_MS_PER_SECOND = 1000

# --- Node names (equal to the routing literals returned by the nodes) ------
NODE_INTAKE_VALIDATE = "intake_validate"
NODE_GATHER_CONTEXT = "gather_context"
NODE_CORRELATE = "correlate"
NODE_FORM_HYPOTHESES = "form_hypotheses"
NODE_PROBE = "probe"
NODE_SYNTHESIZE_VERDICT = "synthesize_verdict"
NODE_DRAFT_ACTION = "draft_action"
NODE_ESCALATE = "escalate"
NODE_EMIT_REPORT = "emit_report"
# Terminal sentinel (equal to LangGraph's END / the emit_report ROUTE_END).
GRAPH_END = "__end__"

# The two convergence nodes on the fail-open path. The total-budget guard is not
# re-applied once a run has entered this path (they lead deterministically to the
# single terminal report), which keeps the driver from re-forcing / looping.
_CONVERGENCE_NODES = frozenset({NODE_ESCALATE, NODE_EMIT_REPORT, GRAPH_END})

# Stage labels recorded on gaps produced by the cross-cutting guards.
STAGE_BUDGET = "budget"
STAGE_FAILURE = "failure"

# Non-committal threat category for a guard-forced ``uncertain`` verdict. Mirrors
# the intake / verdict / emit_report fallbacks: "something is off, undetermined"
# — never a risk downgrade (fail-open-to-human, not fail-open-to-allow).
GUARD_FALLBACK_THREAT_CATEGORY = ThreatCategory.OFF_INTENT_AGENT

# Defensive upper bound on node transitions. The graph is acyclic apart from the
# self-bounded probe loop and the linear escalate→emit_report tail, so a real run
# takes well under a dozen transitions; this cap only guarantees termination even
# if an injected collaborator misbehaves.
_MAX_TRANSITIONS = 1000

# A run may be started from an already-built state or from a raw envelope.
StateOrEnvelope = Union[TriageState, AlertEnvelope, Mapping[str, Any]]


@dataclass(frozen=True)
class GraphCollaborators:
    """The injected collaborators the Investigation_Graph runs against.

    Everything the graph touches that could reach real time, engine I/O, or a
    language model is injected here, so a run is fully deterministic under test
    doubles.

    Attributes:
        registry: The read-only tool registry used by ``gather_context`` and
            ``correlate`` (a ``StaticEngineBackend``-backed registry in tests).
        enforcer: The read-only enforcer used by ``probe`` (the choke point that
            denies out-of-set requests before execution). Defaults to a
            :class:`ReadOnlyEnforcer` wrapping ``registry`` when not supplied.
        hypothesis_model: The language model for ``form_hypotheses``.
        verdict_model: The language model for ``synthesize_verdict``.
        strategy: The probe strategy proposing the next scoped call.
        clock: Injectable epoch-millisecond clock. Used by the total-budget guard
            (R13.3) and passed to the probe wall-clock budget (R8.3/R8.5) so both
            are deterministic. Defaults to :func:`wall_clock_ms`.
        config: Sidecar configuration (defaults to the process config).
        drafter: Optional action-drafting policy for ``draft_action``.
        runner: Optional timeout runner for the two model steps.
        stamper: Optional outgoing schema-version stamper for ``emit_report``.
        schema_version: Optional explicit outgoing schema version (takes
            precedence over ``stamper``).
        escalation_hook: Optional HITL hook invoked by ``escalate`` (task 19 seam).
    """

    registry: ReadToolRegistry
    hypothesis_model: HypothesisModel
    verdict_model: VerdictModel
    strategy: ProbeStrategy
    enforcer: Optional[ReadOnlyEnforcer] = None
    clock: Clock = wall_clock_ms
    config: Optional[SidecarConfig] = None
    drafter: Optional[ActionDrafter] = None
    runner: Optional[TimeoutRunner] = None
    stamper: Optional[MessageStamper] = None
    schema_version: Optional[str] = None
    escalation_hook: Optional[EscalationHook] = None

    def resolved_config(self) -> SidecarConfig:
        """The effective sidecar configuration for this run."""
        return self.config or get_config()

    def resolved_enforcer(self) -> ReadOnlyEnforcer:
        """The read-only enforcer, derived from ``registry`` when not supplied."""
        return self.enforcer or ReadOnlyEnforcer(self.registry)


@dataclass(frozen=True)
class InvestigationOutcome:
    """The result of a completed Investigation_Graph run.

    Attributes:
        state: The final :class:`TriageState` (all evidence, gaps, denials,
            verdict, and any drafted action applied).
        report: The single :class:`Triage_Report` produced by the terminal
            ``emit_report`` node (exactly one per run).
    """

    state: TriageState
    report: Triage_Report


class InvestigationGraph:
    """The assembled Investigation_Graph: a deterministic node-driving state machine.

    Construct with a :class:`GraphCollaborators` bundle, then call :meth:`run`.
    The driver walks the design's state diagram, consuming each node's routing
    literal, and enforces the total-budget, unrecoverable-failure, and read-only
    guards on every transition. Exactly one report is produced per run.
    """

    def __init__(self, collaborators: GraphCollaborators) -> None:
        self._collab = collaborators
        self._config = collaborators.resolved_config()
        self._enforcer = collaborators.resolved_enforcer()
        self._budget_ms = (
            self._config.total_investigation_budget_seconds * _MS_PER_SECOND
        )

    # --- entry point ------------------------------------------------------
    def run(self, state_or_envelope: StateOrEnvelope) -> InvestigationOutcome:
        """Run the investigation to completion and return its outcome.

        Accepts an already-built :class:`TriageState` or a raw alert envelope
        (a parsed :class:`AlertEnvelope` or a mapping); an envelope is wrapped in
        a fresh state keyed by its ``alertId`` and anchored at ``clock()``.

        The whole run is wrapped by the unrecoverable-failure boundary (R13.4):
        any uncaught exception is converted into an ``uncertain`` + ``escalate``
        outcome that still emits exactly one report — the run never crashes.
        """
        state, envelope = self._coerce_start(state_or_envelope)
        try:
            report = self._drive(state, envelope)
        except Exception as exc:  # noqa: BLE001 - graph-boundary guard (R13.4)
            report = self._recover_from_failure(state, exc)
        return InvestigationOutcome(state=state, report=report)

    # --- the driver -------------------------------------------------------
    def _drive(self, state: TriageState, envelope: Optional[AlertEnvelope]) -> Triage_Report:
        """Walk the state machine, returning the single terminal report."""
        current = NODE_INTAKE_VALIDATE
        transitions = 0

        while current != GRAPH_END:
            transitions += 1
            if transitions > _MAX_TRANSITIONS:  # pragma: no cover - defensive
                # Guarantee termination even under a misbehaving collaborator.
                self._force_uncertain(
                    state,
                    reason="investigation exceeded the maximum node-transition bound",
                    stage=STAGE_FAILURE,
                )
                escalate(state, hook=self._collab.escalation_hook)
                return self._emit(state)

            # Cross-cutting total-budget guard (R13.3): checked before every
            # investigative transition, not on the fail-open convergence tail.
            if current not in _CONVERGENCE_NODES and self._budget_exceeded(state):
                self._force_uncertain(
                    state,
                    reason=(
                        "investigation exceeded the total time budget of "
                        f"{self._config.total_investigation_budget_seconds}s"
                    ),
                    stage=STAGE_BUDGET,
                )
                current = NODE_ESCALATE
                continue

            current, report = self._run_node(current, state, envelope)
            if report is not None:
                return report

        # Unreachable: emit_report is the only node that ends the loop and it
        # always returns a report.
        raise RuntimeError("Investigation_Graph terminated without emitting a report")

    def _run_node(
        self,
        current: str,
        state: TriageState,
        envelope: Optional[AlertEnvelope],
    ) -> tuple[str, Optional[Triage_Report]]:
        """Execute one node and return ``(next_node, report_or_None)``.

        Only the terminal ``emit_report`` node yields a report; every other node
        yields the next node name derived from its routing literal.
        """
        if current == NODE_INTAKE_VALIDATE:
            result = intake_validate(state, envelope, config=self._config)
            return result.next, None

        if current == NODE_GATHER_CONTEXT:
            return gather_context(state, self._collab.registry), None

        if current == NODE_CORRELATE:
            correlate(state, self._collab.registry)
            return NODE_FORM_HYPOTHESES, None

        if current == NODE_FORM_HYPOTHESES:
            result = form_hypotheses(
                state,
                self._collab.hypothesis_model,
                config=self._config,
                runner=self._collab.runner,
            )
            return result.route, None

        if current == NODE_PROBE:
            result = probe(
                state,
                self._enforcer,
                strategy=self._collab.strategy,
                clock=self._collab.clock,
                config=self._config,
            )
            return result.next, None

        if current == NODE_SYNTHESIZE_VERDICT:
            result = synthesize_verdict(
                state,
                self._collab.verdict_model,
                config=self._config,
                runner=self._collab.runner,
            )
            return result.route, None

        if current == NODE_DRAFT_ACTION:
            result = draft_action(state, drafter=self._collab.drafter)
            return result.next, None

        if current == NODE_ESCALATE:
            result = escalate(state, hook=self._collab.escalation_hook)
            return result.next, None

        if current == NODE_EMIT_REPORT:
            return GRAPH_END, self._emit(state)

        raise RuntimeError(f"Investigation_Graph reached an unknown node: {current!r}")

    # --- cross-cutting guards --------------------------------------------
    def _budget_exceeded(self, state: TriageState) -> bool:
        """True iff the total investigation budget has been reached (R13.3)."""
        elapsed = self._collab.clock() - state.investigation_started_ms
        return elapsed >= self._budget_ms

    def _force_uncertain(self, state: TriageState, *, reason: str, stage: str) -> None:
        """Force an ``uncertain`` verdict and record a guard gap (R13.3/R13.4).

        Evidence already gathered is left untouched (retained); only the verdict
        is overridden and a gap recorded so the report explains the override.
        """
        state.gaps.append(
            Gap(
                stage=stage,
                element=f"investigation[alertId={state.alertId}]",
                reason=reason,
            )
        )
        state.verdict = Verdict(
            value=VerdictValue.UNCERTAIN,
            confidence=0.0,
            threatCategory=GUARD_FALLBACK_THREAT_CATEGORY,
            malformedRejected=False,
        )

    def _recover_from_failure(self, state: TriageState, exc: Exception) -> Triage_Report:
        """Graph-boundary handler: convert any uncaught error to uncertain+escalate (R13.4).

        Forces an ``uncertain`` verdict, escalates to a human, and still emits a
        single report — retaining any evidence gathered before the failure. If
        emit_report itself cannot run, falls back to a minimally-assembled
        uncertain report so the run never crashes.
        """
        self._force_uncertain(
            state,
            reason=f"unrecoverable failure during investigation: {exc!r}",
            stage=STAGE_FAILURE,
        )
        try:
            escalate(state, hook=self._collab.escalation_hook)
        except Exception:  # noqa: BLE001 - never let recovery itself crash
            state.escalated = True
        try:
            return self._emit(state)
        except Exception:  # noqa: BLE001 - last-resort minimal report
            return self._fallback_report(state)

    # --- report emission --------------------------------------------------
    def _emit(self, state: TriageState) -> Triage_Report:
        """Run the terminal ``emit_report`` node and return its single report."""
        return emit_report(
            state,
            stamper=self._collab.stamper,
            schema_version=self._collab.schema_version,
            config=self._config,
        ).report

    def _fallback_report(self, state: TriageState) -> Triage_Report:
        """Assemble a minimal ``uncertain`` report when emit_report cannot run.

        This defensive path only fires when the terminal node itself raises
        (e.g. an unrecoverable wiring bug leaves the run without a resolvable
        trigger type). It honours the never-crash guarantee by producing a
        well-formed uncertain report rather than propagating the failure.
        """
        trigger_type = state.triggerType or (
            state.envelope.triggerType if state.envelope is not None else None
        )
        if trigger_type is None:
            # A Triage_Report requires a TriggerType; a run without one is a
            # programming error upstream. Re-raise so it is not silently masked.
            raise RuntimeError(
                "cannot assemble a fallback Triage_Report: no triggerType on "
                f"state or envelope (alertId={state.alertId!r})"
            )
        version = self._collab.schema_version or (
            self._collab.stamper.schema_version
            if self._collab.stamper is not None
            else MessageStamper(config=self._config).schema_version
        )
        return Triage_Report(
            alertId=state.alertId,
            triggerType=trigger_type,
            verdict=VerdictValue.UNCERTAIN,
            confidence=0.0,
            threatCategory=GUARD_FALLBACK_THREAT_CATEGORY,
            evidence=[],
            excludedEvidence=[],
            noEvidenceFlag=True,
            recommendedAction=None,
            schemaVersion=version,
        )

    # --- input coercion ---------------------------------------------------
    def _coerce_start(
        self, state_or_envelope: StateOrEnvelope
    ) -> tuple[TriageState, Optional[AlertEnvelope]]:
        """Normalise the run input into ``(state, envelope)``.

        A :class:`TriageState` is used as-is (intake validates ``state.envelope``);
        a parsed :class:`AlertEnvelope` or a mapping is wrapped in a fresh state
        keyed by its ``alertId`` and anchored at ``clock()`` for the budget.
        """
        if isinstance(state_or_envelope, TriageState):
            return state_or_envelope, None

        if isinstance(state_or_envelope, AlertEnvelope):
            envelope = state_or_envelope
        elif isinstance(state_or_envelope, Mapping):
            envelope = AlertEnvelope.model_validate(dict(state_or_envelope))
        else:  # pragma: no cover - defensive
            raise TypeError(
                "run() expects a TriageState, an AlertEnvelope, or a mapping; "
                f"got {type(state_or_envelope).__name__}"
            )

        state = TriageState(
            alertId=envelope.alertId,
            investigation_started_ms=self._collab.clock(),
        )
        return state, envelope


def build_investigation_graph(collaborators: GraphCollaborators) -> InvestigationGraph:
    """Build an :class:`InvestigationGraph` from an injected collaborators bundle."""
    return InvestigationGraph(collaborators)


def run_investigation(
    state_or_envelope: StateOrEnvelope,
    *,
    registry: ReadToolRegistry,
    hypothesis_model: HypothesisModel,
    verdict_model: VerdictModel,
    strategy: ProbeStrategy,
    enforcer: Optional[ReadOnlyEnforcer] = None,
    clock: Clock = wall_clock_ms,
    config: Optional[SidecarConfig] = None,
    drafter: Optional[ActionDrafter] = None,
    runner: Optional[TimeoutRunner] = None,
    stamper: Optional[MessageStamper] = None,
    schema_version: Optional[str] = None,
    escalation_hook: Optional[EscalationHook] = None,
) -> InvestigationOutcome:
    """Top-level entry: run one investigation and return its outcome.

    Wires the injected collaborators into an :class:`InvestigationGraph` and runs
    it against ``state_or_envelope`` (a :class:`TriageState`, an
    :class:`AlertEnvelope`, or a mapping). Returns the :class:`InvestigationOutcome`
    carrying the final state and the single :class:`Triage_Report`.

    All time, engine I/O, and model access is injected, so a run is fully
    deterministic under test doubles; ``clock`` is the deterministic hook for the
    total-budget guard (R13.3) and the probe wall-clock budget (R8.3/R8.5).
    """
    collaborators = GraphCollaborators(
        registry=registry,
        hypothesis_model=hypothesis_model,
        verdict_model=verdict_model,
        strategy=strategy,
        enforcer=enforcer,
        clock=clock,
        config=config,
        drafter=drafter,
        runner=runner,
        stamper=stamper,
        schema_version=schema_version,
        escalation_hook=escalation_hook,
    )
    return build_investigation_graph(collaborators).run(state_or_envelope)


__all__ = [
    # node-name / sentinel constants
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
    # public API
    "GraphCollaborators",
    "InvestigationOutcome",
    "InvestigationGraph",
    "build_investigation_graph",
    "run_investigation",
]
