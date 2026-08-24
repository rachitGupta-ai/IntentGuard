"""The ``intake_validate`` Investigation_Graph node (task 9.1, R4.1/R4.2/R4.4).

``intake_validate`` is the **first** node of the ``Investigation_Graph`` and the
gatekeeper that guarantees *no Read_Tool is ever invoked against a malformed
alert*. It validates the ``AlertEnvelope`` against the versioned
``Integration_Contract`` schema **before any tool call** and then either records
the validated envelope in state and routes to context gathering, or halts and
fails open to a human.

Behavior (R4.1, R4.2, R4.4)
---------------------------
*Before any Read_Tool is invoked* the node validates the envelope by:

  1. Ensuring an envelope payload is present (a parsed :class:`AlertEnvelope`,
     a raw mapping to parse, or a previously-recorded ``state.envelope``).
  2. Parsing/validating it against the ``AlertEnvelope`` schema - confirming the
     presence and type of every required field, including a non-empty
     ``alertId`` and a recognized ``triggerType`` (R4.1).
  3. Confirming the declared ``schemaVersion`` is one the sidecar supports - the
     *versioned* part of the Integration_Contract (R4.1, R14.5).
  4. Confirming the envelope's ``alertId`` matches the ``alertId`` this run is
     keyed by, so traceability is not corrupted.

**On success (R4.4):** the validated envelope is recorded in ``state.envelope``
and its ``triggerType`` in ``state.triggerType`` *before* context is gathered,
and the node routes to :data:`ROUTE_GATHER_CONTEXT`.

**On failure (R4.2):** the node invokes **no** Read_Tool. It records a
:class:`~sidecar.models.Gap` naming the failed check and the ``alertId``, sets an
``uncertain`` :class:`~sidecar.models.Verdict` (``confidence == 0.0``), marks the
run ``escalated``, and routes to :data:`ROUTE_ESCALATE`. The downstream
``emit_report`` node (task 17) turns that state into the ``uncertain``
``Triage_Report`` referencing the ``alertId`` and the failed check, and the
escalate/HITL path (tasks 16/19) delivers it to a human (fail-open-to-human).

This node performs **zero** tool access; it imports nothing from
``sidecar.tools`` by design.

Routing convention
------------------
The node returns an :class:`IntakeResult` exposing the updated ``state`` and a
``next`` routing literal - one of :data:`ROUTE_GATHER_CONTEXT` or
:data:`ROUTE_ESCALATE`. The literals equal the target node names, so a LangGraph
conditional edge can branch on the returned ``next`` value directly, or call
:func:`route_after_intake` to re-derive the same decision from state (it routes
to escalate iff ``state.escalated`` is set). Tasks 10.1 and 20.1 follow this
convention.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional, Union

from pydantic import ValidationError

from sidecar.config import SidecarConfig, get_config
from sidecar.models import (
    AlertEnvelope,
    Gap,
    ThreatCategory,
    TriageState,
    Verdict,
    VerdictValue,
)

# --- Routing literals (equal to the target node names) --------------------
ROUTE_GATHER_CONTEXT = "gather_context"
ROUTE_ESCALATE = "escalate"

# The investigation stage recorded on gaps produced by this node.
INTAKE_STAGE = "intake"

# Threat category placeholder for the intake-failure ``uncertain`` verdict.
#
# The ``Verdict`` schema requires a ``threatCategory`` from the permitted set,
# but an envelope that fails intake validation cannot be classified. We attach a
# non-committal placeholder together with ``value == uncertain`` and
# ``confidence == 0.0`` so the verdict asserts nothing about risk - the human the
# run escalates to makes the call. We deliberately do **not** use
# ``benign-anomaly`` or ``false-positive`` here, since those would wrongly
# *downgrade* risk (fail-open-to-allow); ``off-intent-agent`` reads as "something
# is off, undetermined" and never weakens enforcement.
INTAKE_FAILURE_THREAT_CATEGORY = ThreatCategory.OFF_INTENT_AGENT

# Raw envelope input may arrive already parsed, as a mapping to parse, or absent.
EnvelopeInput = Union[AlertEnvelope, Mapping[str, Any], None]


@dataclass(frozen=True)
class IntakeResult:
    """The outcome of the ``intake_validate`` node.

    Attributes:
        state: The updated :class:`TriageState`. On success it carries the
            recorded ``envelope`` and ``triggerType`` (R4.4); on failure it
            carries an ``uncertain`` verdict, the intake gap, and
            ``escalated == True`` (R4.2).
        next: The routing literal for the next node - :data:`ROUTE_GATHER_CONTEXT`
            on success, :data:`ROUTE_ESCALATE` on failure.
        valid: Whether the envelope passed validation.
        failedCheck: On failure, a short name of the check that failed
            (``None`` on success).
    """

    state: TriageState
    next: str
    valid: bool
    failedCheck: Optional[str] = None

    @property
    def escalated(self) -> bool:
        """True iff the node routed to escalation (invalid envelope)."""
        return self.next == ROUTE_ESCALATE


def intake_validate(
    state: TriageState,
    envelope: EnvelopeInput = None,
    *,
    config: Optional[SidecarConfig] = None,
) -> IntakeResult:
    """Validate the alert envelope before any Read_Tool runs (R4.1/R4.2/R4.4).

    Args:
        state: The investigation state for this run (keyed by ``state.alertId``).
        envelope: The envelope to validate. May be an already-parsed
            :class:`AlertEnvelope`, a raw mapping to parse, or ``None`` to fall
            back to a previously-recorded ``state.envelope``.
        config: Optional sidecar configuration (for the supported schema-version
            set); defaults to the process-wide configuration.

    Returns:
        An :class:`IntakeResult`. On success ``state.envelope`` and
        ``state.triggerType`` are set and ``next`` is
        :data:`ROUTE_GATHER_CONTEXT`. On failure the state carries an
        ``uncertain`` verdict + intake gap + ``escalated`` and ``next`` is
        :data:`ROUTE_ESCALATE`.

    Note:
        This node never invokes a Read_Tool; on any validation failure it halts
        before context gathering (R4.2).
    """

    cfg = config or get_config()

    parsed, failed_check = _validate_envelope(state, envelope, cfg)

    if parsed is None:
        # Validation failed: halt before any tool call and fail open to a human.
        return _fail(state, failed_check or "envelope validation failed")

    # Success: record the validated envelope and trigger type BEFORE context
    # gathering (R4.4), then route to gather_context.
    state.envelope = parsed
    state.triggerType = parsed.triggerType
    return IntakeResult(state=state, next=ROUTE_GATHER_CONTEXT, valid=True)


def route_after_intake(state: TriageState) -> str:
    """Re-derive the intake routing decision from state (for LangGraph edges).

    Routes to :data:`ROUTE_ESCALATE` iff the run has been escalated (an invalid
    envelope), otherwise to :data:`ROUTE_GATHER_CONTEXT`. This mirrors the
    ``next`` value returned by :func:`intake_validate`.
    """
    return ROUTE_ESCALATE if state.escalated else ROUTE_GATHER_CONTEXT


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _validate_envelope(
    state: TriageState,
    envelope: EnvelopeInput,
    cfg: SidecarConfig,
) -> tuple[Optional[AlertEnvelope], Optional[str]]:
    """Validate the envelope; return ``(parsed, None)`` or ``(None, failedCheck)``.

    The order of checks is deliberate: presence -> schema parse -> supported
    version -> alertId match. The first failure short-circuits with a
    human-readable ``failedCheck`` naming what went wrong.
    """

    # 1. Presence: an envelope payload must be available to validate.
    candidate: EnvelopeInput = envelope if envelope is not None else state.envelope
    if candidate is None:
        return None, "missing envelope"

    # 2. Schema parse: confirm presence/type of every required field, a
    #    non-empty alertId, and a recognized triggerType (R4.1).
    if isinstance(candidate, AlertEnvelope):
        parsed = candidate
    else:
        try:
            parsed = AlertEnvelope.model_validate(dict(candidate))
        except ValidationError as exc:
            field = _first_offending_field(exc)
            return None, f"invalid envelope field: {field}"

    # 3. Versioned Integration_Contract: the declared schemaVersion must be
    #    supported (R4.1, R14.5).
    if not cfg.is_supported_version(parsed.schemaVersion):
        return None, f"unsupported schemaVersion: {parsed.schemaVersion!r}"

    # 4. Traceability: the envelope's alertId must match the run's alertId.
    if parsed.alertId != state.alertId:
        return None, (
            f"alertId mismatch: envelope {parsed.alertId!r} != run {state.alertId!r}"
        )

    return parsed, None


def _fail(state: TriageState, failed_check: str) -> IntakeResult:
    """Record the intake failure on state and route to escalation (R4.2).

    Sets an ``uncertain`` verdict (confidence ``0.0``), appends a gap naming the
    failed check and the alertId, and marks the run escalated - all without
    invoking any Read_Tool.
    """

    alert_id = state.alertId

    state.gaps.append(
        Gap(
            stage=INTAKE_STAGE,
            element=f"envelope[alertId={alert_id}]",
            reason=failed_check,
        )
    )
    state.verdict = Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=0.0,
        threatCategory=INTAKE_FAILURE_THREAT_CATEGORY,
        malformedRejected=True,
    )
    state.escalated = True

    return IntakeResult(
        state=state,
        next=ROUTE_ESCALATE,
        valid=False,
        failedCheck=failed_check,
    )


def _first_offending_field(exc: ValidationError) -> str:
    """Best-effort extraction of the first offending field name from an error."""
    for err in exc.errors():
        loc = err.get("loc", ())
        if loc:
            return ".".join(str(p) for p in loc)
    return "unknown"


__all__ = [
    "intake_validate",
    "route_after_intake",
    "IntakeResult",
    "ROUTE_GATHER_CONTEXT",
    "ROUTE_ESCALATE",
    "INTAKE_STAGE",
    "INTAKE_FAILURE_THREAT_CATEGORY",
    "EnvelopeInput",
]
