"""Top-level Alert Triage Sidecar application assembly (task 20.3).

This module wires the versioned ``Integration_Contract`` adapter to the
idempotency store, the ``Investigation_Graph``, the outbound report-emission
channel, and the inbound Control_Tower decision channel, so the sidecar
processes an alert **end to end**:

    inbound alert delivery
      -> schema-version validation (R14.5)
      -> trigger classification (R2)
      -> idempotency admission (R3)
      -> [NEW] Investigation_Graph run (R4-R9)
      -> mark completed (R3.2/R3.6)
      -> report emission to Control_Tower (R10, stamped + Service_Account auth)
      -> InboundResponse (201 / 200 / 202 / 400 / 409)

and, on the return channel:

    Control_Tower decision (approve/reject)
      -> parse_decision
      -> dual-control (HITLManager.submit_decision, R11.4/R11.5)
      -> resume from checkpoint when two distinct approvers approve (R11.3)

Fail-open-to-human / enforcement independence (R13.1, R13.2, R13.5)
-------------------------------------------------------------------
This wiring is a **one-way notification + advisory return channel**. It is a
*structural* guarantee that the sidecar never touches IntentGuard enforcement:

  * The only outbound capabilities assembled here are (a) the read-only tool
    layer (``ReadToolRegistry`` over a ``ReadOnlyEngineBackend`` — five read
    tools, zero write/block/enforcement tools) and (b) the report / decision
    channels (advisory ``Triage_Report`` emission and a decision *ingress*).
  * Every engine-directed request authenticates with the read-only
    ``Service_Account`` credential (``MessageStamper`` — R14.2) and carries the
    schema version (R14.1). The credential's scope set is closed to read-only
    scopes, so no code path here can express a write/block/enforce operation.
  * There is **no call site** — none — from this app into any engine
    write/enforcement API. If the sidecar is unavailable, unreachable, slow, or
    crashing, the engine's fail-closed decision (made independently, before the
    sidecar ever runs) is unaffected: the app simply never runs, and nothing it
    could do would allow, unblock, or exempt a command from enforcement.

Everything is injectable with in-memory defaults so production wires real
collaborators (a real engine backend, real LLMs, a durable checkpoint/idempotency
store, a real HTTP report transport) while tests wire deterministic doubles. The
in-memory defaults are deliberately *fail-open-to-human*: with no real language
model wired, verdict synthesis fails open to an ``uncertain`` verdict that
escalates to a human (never a risk downgrade).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional

from sidecar.config import SidecarConfig, get_config
from sidecar.contract import (
    AdmissionResult as ContractAdmissionResult,
    AdmissionState as ContractAdmissionState,
    ControlTowerDecision,
    DecisionAck,
    DecisionMessage,
    IdempotencyAdmitter,
    InboundAlertHandler,
    InboundResponse,
    MessageStamper,
    RejectionRecorder,
    ReportEmitter,
    ReportTransport,
    ServiceAccountCredential,
    StampingReportEmitter,
    TriggerClassifier as ContractTriggerClassifier,
    TriggerDecision,
    default_service_account,
    parse_decision,
)
from sidecar.hitl import (
    CheckpointNotRestoredError,
    HITLManager,
    StateNotPersistedError,
)
from sidecar.models import AlertEnvelope, Triage_Report, TriageState
from sidecar.tools import (
    InlineTimeoutRunner,
    ReadOnlyEnforcer,
    ReadToolRegistry,
    StaticEngineBackend,
    TimeoutRunner,
)
from sidecar.triage import (
    AdmissionOutcome,
    IdempotencyStore,
    InvestigationOutcome,
    TriggerClassifier as RawTriggerClassifier,
    run_investigation,
)
from sidecar.triage.nodes import (
    ActionDrafter,
    EscalationHook,
    HypothesisModel,
    ProbeStrategy,
    ScriptedProbeStrategy,
    StaticHypothesisModel,
    StaticVerdictModel,
    VerdictModel,
)
from sidecar.triage.nodes.probe import Clock, wall_clock_ms


# ---------------------------------------------------------------------------
# Adapter: raw trigger classifier -> contract TriggerClassifier Protocol
# ---------------------------------------------------------------------------


class TriggerClassifierAdapter:
    """Adapts the raw-event :class:`RawTriggerClassifier` to the contract Protocol.

    The contract's inbound handler validates the schema version and parses the
    :class:`AlertEnvelope` *before* trigger classification (the design's
    lifecycle: schema-version validation -> trigger classification -> idempotency
    admission). So this adapter receives an **already-parsed** envelope and must
    expose ``classify(AlertEnvelope) -> TriggerDecision``.

    Rather than duplicate the R2 admit/discard/reject logic, it reconstructs the
    raw event mapping the real :class:`RawTriggerClassifier` expects and delegates
    to it, then maps the :class:`~sidecar.triage.trigger.ClassificationResult`
    onto a :class:`TriggerDecision`:

      * ``ADMIT``   -> ``TriggerDecision(admitted=True)``.
      * ``DISCARD`` -> ``TriggerDecision(admitted=False, missingField="triggerType")``
        (the event carries none of the four Triage_Trigger conditions, R2.5).
      * ``REJECT``  -> ``TriggerDecision(admitted=False, missingField=<field>)``
        (a malformed alert, R2.6) — carrying the offending field/reason.

    Because the envelope has already passed Pydantic validation (non-empty
    ``alertId`` and a valid ``TriggerType``), the real classifier admits it; the
    adapter keeps the genuine classifier in the loop and records its rejections.
    """

    def __init__(self, classifier: Optional[RawTriggerClassifier] = None) -> None:
        self._classifier = classifier or RawTriggerClassifier()

    @property
    def classifier(self) -> RawTriggerClassifier:
        """The wrapped raw classifier (its ``rejections`` ledger is auditable)."""
        return self._classifier

    def classify(self, envelope: AlertEnvelope) -> TriggerDecision:
        result = self._classifier.classify(self._raw_event(envelope))
        if result.admitted:
            return TriggerDecision(admitted=True)
        if result.discarded:
            return TriggerDecision(
                admitted=False,
                missingField="triggerType",
                detail="event carries none of the four Triage_Trigger conditions (R2.5)",
            )
        # REJECT (malformed) — surface the offending field/reason (R2.6).
        rejection = result.rejection
        return TriggerDecision(
            admitted=False,
            missingField=rejection.missingField if rejection is not None else "triggerType",
            detail=rejection.reason if rejection is not None else None,
        )

    @staticmethod
    def _raw_event(envelope: AlertEnvelope) -> dict[str, Any]:
        """Reconstruct the raw event mapping the raw classifier evaluates."""
        return {
            "alertId": envelope.alertId,
            "triggerType": envelope.triggerType,
        }


# ---------------------------------------------------------------------------
# Adapter: idempotency store -> contract IdempotencyAdmitter Protocol
# ---------------------------------------------------------------------------


class InvestigatingAdmitter:
    """Adapts :class:`IdempotencyStore` to the contract :class:`IdempotencyAdmitter`.

    Maps the store's :class:`AdmissionOutcome` onto the contract's
    :class:`ContractAdmissionState` and, on a **NEW** admission, drives the whole
    investigation before returning:

      * ``ADMITTED_NEW`` -> run the ``Investigation_Graph`` via
        :func:`run_investigation` with the injected collaborators, then
        ``mark_completed(alertId, report)`` (R3.2/R3.6) and **emit** the report to
        the Control_Tower via the injected :class:`ReportEmitter` (stamped +
        Service_Account auth, R10/R14.1/R14.2). Returns
        ``AdmissionState.NEW`` -> the inbound handler answers ``201 accepted``.
      * ``IN_PROGRESS`` -> ``AdmissionState.IN_PROGRESS`` -> ``202 in_progress`` (R3.3).
      * ``COMPLETED``   -> the stored report is returned -> ``200`` with the report
        (R3.2, R3.6).

    The idempotency store admits at most one active run per ``alertId`` via an
    atomic compare-and-set (R3.4/R3.5), so exactly one investigation runs per
    alert; duplicates within retention return the identical stored report.
    """

    def __init__(
        self,
        store: IdempotencyStore,
        emitter: ReportEmitter,
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
        escalation_hook: Optional[EscalationHook] = None,
    ) -> None:
        self._store = store
        self._emitter = emitter
        self._registry = registry
        self._hypothesis_model = hypothesis_model
        self._verdict_model = verdict_model
        self._strategy = strategy
        self._enforcer = enforcer
        self._clock = clock
        self._config = config or get_config()
        self._drafter = drafter
        self._runner = runner
        self._stamper = stamper
        self._escalation_hook = escalation_hook

    def admit(self, envelope: AlertEnvelope) -> ContractAdmissionResult:
        result = self._store.admit(envelope.alertId)

        if result.outcome is AdmissionOutcome.ADMITTED_NEW:
            report = self._investigate_and_emit(envelope)
            return ContractAdmissionResult(state=ContractAdmissionState.NEW, report=report)

        if result.outcome is AdmissionOutcome.IN_PROGRESS:
            return ContractAdmissionResult(state=ContractAdmissionState.IN_PROGRESS)

        # AdmissionOutcome.COMPLETED — the run completed within retention (R3.2/R3.6).
        return ContractAdmissionResult(
            state=ContractAdmissionState.COMPLETED,
            report=result.report,
        )

    def _investigate_and_emit(self, envelope: AlertEnvelope) -> Triage_Report:
        """Run the investigation, persist completion, and emit the report."""
        outcome: InvestigationOutcome = run_investigation(
            envelope,
            registry=self._registry,
            hypothesis_model=self._hypothesis_model,
            verdict_model=self._verdict_model,
            strategy=self._strategy,
            enforcer=self._enforcer,
            clock=self._clock,
            config=self._config,
            drafter=self._drafter,
            runner=self._runner,
            stamper=self._stamper,
            escalation_hook=self._escalation_hook,
        )
        report = outcome.report
        # Store the completed report so duplicate triggers within retention return
        # the identical report (R3.2, R3.6).
        self._store.mark_completed(envelope.alertId, report)
        # Emit the advisory report to the Control_Tower — stamped with the schema
        # version and authenticated with the read-only Service_Account (R10, R14).
        self._emitter.emit(report)
        return report


# ---------------------------------------------------------------------------
# HITL escalation hook: persist-then-pause when the graph escalates
# ---------------------------------------------------------------------------


class _HITLEscalationHook:
    """Bridges the ``escalate`` node to the :class:`HITLManager` (R11.1).

    When the Investigation_Graph escalates to a human, this hook persists the
    investigation state via the HITL manager's checkpoint store and pauses the
    run (persist-then-pause). A failure to persist durably is surfaced as a
    :class:`StateNotPersistedError`; the graph's unrecoverable-failure boundary
    still emits an ``uncertain`` report so the run never crashes, and the paused
    state (if any) is retained for the decision channel to resume (R11.2/R11.3).
    """

    def __init__(self, hitl: HITLManager) -> None:
        self._hitl = hitl

    def on_escalate(self, state: TriageState) -> None:
        self._hitl.escalate_and_pause(state)


# ---------------------------------------------------------------------------
# Control_Tower decision channel result
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class DecisionResult:
    """The outcome of handling one Control_Tower decision (R11.3-R11.5).

    Attributes:
        ack: The :class:`DecisionAck` returned to the Control_Tower.
        mayProceed: Whether dual-control now permits the Recommended_Action to
            proceed (two distinct approvers approved, R11.4).
        resumed: Whether the investigation was resumed from its checkpoint (R11.3).
        resumedState: The restored :class:`TriageState` when resumed, else ``None``.
    """

    ack: DecisionAck
    mayProceed: bool
    resumed: bool
    resumedState: Optional[TriageState] = None


# ---------------------------------------------------------------------------
# The assembled sidecar application
# ---------------------------------------------------------------------------


class Sidecar:
    """The assembled Alert Triage Sidecar (task 20.3).

    Assembles the versioned ``Integration_Contract`` inbound handler, the
    idempotency store, the ``Investigation_Graph`` (via injected collaborators),
    the outbound report emitter, and the Control_Tower decision channel + HITL
    manager into a single object that processes an alert end to end.

    All collaborators are injectable; the defaults are dependency-free in-memory
    doubles that keep the sidecar exercisable on its own while remaining
    fail-open-to-human (the default verdict model escalates every alert to a
    human rather than producing an autonomous recommendation).

    Public entry points:
      * :meth:`handle_alert` — process one inbound alert message end to end and
        return the design's ``201 / 200 / 202 / 400 / 409`` response.
      * :meth:`handle_decision` — process one Control_Tower decision (approve /
        reject), feed dual-control, and resume from the checkpoint on approval.
    """

    def __init__(
        self,
        *,
        config: Optional[SidecarConfig] = None,
        service_account: Optional[ServiceAccountCredential] = None,
        schema_version: Optional[str] = None,
        # Contract-side collaborators
        stamper: Optional[MessageStamper] = None,
        report_transport: Optional[ReportTransport] = None,
        report_emitter: Optional[ReportEmitter] = None,
        rejection_recorder: Optional[RejectionRecorder] = None,
        # Idempotency + HITL
        idempotency_store: Optional[IdempotencyStore] = None,
        hitl_manager: Optional[HITLManager] = None,
        # Investigation_Graph collaborators (production wires real ones)
        registry: Optional[ReadToolRegistry] = None,
        hypothesis_model: Optional[HypothesisModel] = None,
        verdict_model: Optional[VerdictModel] = None,
        strategy: Optional[ProbeStrategy] = None,
        enforcer: Optional[ReadOnlyEnforcer] = None,
        drafter: Optional[ActionDrafter] = None,
        runner: Optional[TimeoutRunner] = None,
        clock: Clock = wall_clock_ms,
        raw_trigger_classifier: Optional[RawTriggerClassifier] = None,
    ) -> None:
        self._config = config or get_config()

        # --- outgoing message stamping + Service_Account auth (R14.1, R14.2) ---
        credential = service_account or default_service_account()
        self._stamper = stamper or MessageStamper(
            config=self._config,
            schema_version=schema_version,
            credential=credential,
        )

        # --- outbound report-emission channel (R10, R14) ---------------------
        self._report_emitter: ReportEmitter = report_emitter or StampingReportEmitter(
            stamper=self._stamper,
            transport=report_transport,
        )

        # --- idempotency store + HITL manager (R3, R11) ----------------------
        self._idempotency = idempotency_store or IdempotencyStore(
            config=self._config, clock=lambda: self._clock_seconds()
        )
        self._clock = clock
        self._hitl = hitl_manager or HITLManager()

        # --- Investigation_Graph collaborators (in-memory, fail-open defaults) ---
        self._registry = registry or ReadToolRegistry(
            StaticEngineBackend(), config=self._config, runner=InlineTimeoutRunner()
        )
        self._hypothesis_model = hypothesis_model or StaticHypothesisModel([])
        # Default verdict model returns nothing -> synthesize_verdict fails open to
        # an uncertain verdict that escalates to a human (fail-open-to-human).
        self._verdict_model = verdict_model or StaticVerdictModel(None)
        self._strategy = strategy or ScriptedProbeStrategy([])
        self._enforcer = enforcer
        self._drafter = drafter
        self._runner = runner or InlineTimeoutRunner()

        # --- HITL escalation hook (persist-then-pause on escalate) -----------
        self._escalation_hook = _HITLEscalationHook(self._hitl)

        # --- idempotency admitter (drives the investigation on NEW) ----------
        self._admitter: IdempotencyAdmitter = InvestigatingAdmitter(
            self._idempotency,
            self._report_emitter,
            registry=self._registry,
            hypothesis_model=self._hypothesis_model,
            verdict_model=self._verdict_model,
            strategy=self._strategy,
            enforcer=self._enforcer,
            clock=clock,
            config=self._config,
            drafter=self._drafter,
            runner=self._runner,
            stamper=self._stamper,
            escalation_hook=self._escalation_hook,
        )

        # --- trigger classifier adapter (R2) ---------------------------------
        self._trigger_classifier: ContractTriggerClassifier = TriggerClassifierAdapter(
            raw_trigger_classifier
        )

        # --- inbound Integration_Contract handler (R14.5, R2, R3) ------------
        self._inbound = InboundAlertHandler(
            self._config,
            trigger_classifier=self._trigger_classifier,
            idempotency_admitter=self._admitter,
            rejection_recorder=rejection_recorder,
        )

    # -- accessors (useful for wiring/tests) -------------------------------

    @property
    def inbound(self) -> InboundAlertHandler:
        """The inbound Integration_Contract handler."""
        return self._inbound

    @property
    def idempotency(self) -> IdempotencyStore:
        """The idempotency store keying runs by ``alertId`` (R3)."""
        return self._idempotency

    @property
    def hitl(self) -> HITLManager:
        """The checkpoint / human-in-the-loop manager (R11)."""
        return self._hitl

    @property
    def report_emitter(self) -> ReportEmitter:
        """The outbound report-emission channel to the Control_Tower (R10)."""
        return self._report_emitter

    @property
    def stamper(self) -> MessageStamper:
        """The outgoing-message stamper (schema version + Service_Account auth)."""
        return self._stamper

    # -- inbound alert delivery (R14.5 -> R2 -> R3 -> R4..R9 -> R10) --------

    def handle_alert(self, message: Any) -> InboundResponse:
        """Process one inbound alert message end to end (R14.5, R2, R3, R4-R10).

        Delegates to the inbound handler, which validates the schema version,
        parses the envelope, classifies the trigger, and admits via the
        investigating admitter — on a new admission the investigation runs, the
        report is stored and emitted, and a ``201`` is returned; a completed run
        within retention returns ``200`` with the stored report; an in-progress
        run returns ``202``; malformed input returns ``400``; an unsupported
        schema version returns ``409``.
        """
        return self._inbound.handle(message)

    # -- Control_Tower decision channel (R11.3, R11.4, R11.5) --------------

    def handle_decision(self, alert_id: str, body: Mapping[str, Any]) -> DecisionResult:
        """Process one Control_Tower decision and resume on dual-control approval.

        Parses the decision body (:func:`parse_decision`), feeds it to
        dual-control via :meth:`HITLManager.submit_decision` (R11.4/R11.5), and —
        only when two distinct approvers have approved — resumes the investigation
        from its persisted checkpoint via :meth:`HITLManager.resume` (R11.3). A
        rejection is recorded and never resumes (R11.5). A checkpoint that is
        unretrievable or fails integrity keeps the investigation paused (R11.8).

        :raises ValueError: if the decision body is malformed (a transport shim
            maps this onto a ``400``).
        """
        decision: DecisionMessage = parse_decision(alert_id, dict(body))
        outcome = self._hitl.submit_decision(decision)

        if not outcome.mayProceed:
            detail = outcome.detail or (
                "decision recorded; awaiting a second distinct approver (R11.4)"
                if decision.decision is ControlTowerDecision.APPROVE
                else "rejection recorded; action will not proceed (R11.5)"
            )
            return DecisionResult(
                ack=DecisionAck(alertId=alert_id, accepted=outcome.accepted, detail=detail),
                mayProceed=False,
                resumed=False,
            )

        # Two distinct approvers approved -> resume from the checkpoint (R11.3).
        try:
            resumed_state = self._hitl.resume(alert_id, decision)
        except CheckpointNotRestoredError as exc:
            # Stay paused; report that the checkpoint could not be restored (R11.8).
            return DecisionResult(
                ack=DecisionAck(
                    alertId=alert_id,
                    accepted=True,
                    detail=f"dual-control approved but checkpoint not restored: {exc}",
                ),
                mayProceed=True,
                resumed=False,
            )

        return DecisionResult(
            ack=DecisionAck(
                alertId=alert_id,
                accepted=True,
                detail="dual-control approved; investigation resumed from checkpoint (R11.3)",
            ),
            mayProceed=True,
            resumed=True,
            resumedState=resumed_state,
        )

    # -- helpers -----------------------------------------------------------

    def _clock_seconds(self) -> float:
        """Idempotency retention clock, in seconds, derived from the ms clock."""
        return self._clock() / 1000.0


__all__ = [
    "Sidecar",
    "TriggerClassifierAdapter",
    "InvestigatingAdmitter",
    "DecisionResult",
    "StateNotPersistedError",
    "CheckpointNotRestoredError",
]
