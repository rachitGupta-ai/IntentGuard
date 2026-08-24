"""The ``synthesize_verdict`` Investigation_Graph node (task 14.1, R9).

When the ``Probe_Loop`` ends, this node asks the language model to synthesize a
:class:`~sidecar.models.Verdict` for the alert, then puts that raw output through
a **two-stage guard** before it can ever become a decision (R9.1-R9.6).

The two-stage guard (design: "Verdict Synthesizer")
---------------------------------------------------
1. **Schema validation + clamping (R9.2).** The raw model output is validated
   against the :class:`~sidecar.models.Verdict` output schema, which requires a
   ``value`` from the permitted :class:`~sidecar.models.VerdictValue` set, a
   *numeric* ``confidence``, and a ``threatCategory`` from the permitted
   :class:`~sidecar.models.ThreatCategory` set. The schema **clamps** any
   confidence ``< 0.0`` to ``0.0`` and ``> 1.0`` to ``1.0``.

2. **Independent malformed classification (R9.3).** *Separately from* schema
   validation, the raw output is classified as malformed when the ``value`` is
   out of set, the ``threatCategory`` is out of set, the ``confidence`` is
   missing or non-numeric, or any required field is absent. This check runs on
   the raw output directly, so **malformed handling fires even when the output
   would otherwise pass schema validation** (R9.3).

Fail-open-to-human (R9.4, R9.6)
-------------------------------
If the model call fails/times out, the output is unparseable, it fails schema
validation, or it is classified as malformed, the node forces
``Verdict(value=uncertain, confidence=0.0, malformedRejected=True)``, marks the
run ``escalated``, and routes to :data:`ROUTE_ESCALATE` (R9.4). Any ``uncertain``
verdict - whether forced or legitimately produced by the model - escalates to a
human and drafts **no** autonomous :class:`~sidecar.models.Recommended_Action`
(R9.6); action drafting for a confident verdict is the ``draft_action`` node's
job (task 16). A confident, well-formed, non-uncertain verdict routes to
:data:`ROUTE_DRAFT_ACTION`.

Testability
-----------
All LLM I/O sits behind the injectable :class:`VerdictModel` protocol so the
graph and its tests run without external dependencies.
:class:`StaticVerdictModel` is the deterministic double: it returns a canned raw
verdict (or raises a supplied error / :class:`ToolTimeout`) so tests can drive
the well-formed, out-of-set, missing-confidence, non-numeric, malformed,
unparseable, error, and timeout paths. The per-tool wall-clock timeout is
enforced through the same injectable :class:`TimeoutRunner` the read-only tool
layer and ``form_hypotheses`` use.

Routing convention
------------------
The node returns a :class:`SynthesizeVerdictResult` exposing the updated
``state`` and a ``route`` literal - one of :data:`ROUTE_DRAFT_ACTION` or
:data:`ROUTE_ESCALATE` - matching the constant-name + result-object convention
of the other graph nodes. :func:`route_after_synthesize_verdict` re-derives the
same decision from ``state`` for a LangGraph conditional edge.
"""

from __future__ import annotations

import numbers
from dataclasses import dataclass
from typing import Any, Mapping, Optional, Protocol, runtime_checkable

from pydantic import ValidationError

from sidecar.config import SidecarConfig, get_config
from sidecar.models import (
    Evidence_Item,
    Gap,
    ThreatCategory,
    TriageState,
    Verdict,
    VerdictValue,
)
from sidecar.tools.timeout import ThreadTimeoutRunner, TimeoutRunner, ToolTimeout

# --- Node identifier + routing literals (equal to the target node names) ---
NODE_SYNTHESIZE_VERDICT = "synthesize_verdict"
ROUTE_DRAFT_ACTION = "draft_action"
ROUTE_ESCALATE = "escalate"

# The investigation stage recorded on gaps this node produces.
STAGE_VERDICT = "verdict"

# Threat category attached to a forced ``uncertain`` verdict when the model
# output cannot be trusted to classify risk. Mirrors the intake node's choice:
# a non-committal "something is off, undetermined" label that never downgrades
# risk (fail-open-to-human, not fail-open-to-allow).
MALFORMED_FALLBACK_THREAT_CATEGORY = ThreatCategory.OFF_INTENT_AGENT

# Required fields of the verdict output schema (R9.3).
_REQUIRED_FIELDS = ("value", "confidence", "threatCategory")


@dataclass(frozen=True)
class VerdictRequest:
    """The read-only view of investigation state handed to the model.

    All fields are analysis data derived from already-gathered, record-id-bound
    evidence and hypotheses. Content is treated strictly as ``Untrusted_Content``
    for analysis (R12.1/R12.2); the model is asked only to *propose* a verdict,
    never to act.
    """

    alertId: str
    triggerType: Optional[str] = None
    context: tuple[Evidence_Item, ...] = ()
    correlations: tuple[Evidence_Item, ...] = ()
    evidence: tuple[Evidence_Item, ...] = ()
    hypothesisStatements: tuple[str, ...] = ()

    @classmethod
    def from_state(cls, state: TriageState) -> "VerdictRequest":
        return cls(
            alertId=state.alertId,
            triggerType=state.triggerType.value if state.triggerType else None,
            context=tuple(state.context),
            correlations=tuple(state.correlations),
            evidence=tuple(state.evidence),
            hypothesisStatements=tuple(h.statement for h in state.hypotheses),
        )


@runtime_checkable
class VerdictModel(Protocol):
    """The injectable language-model interface used to synthesize a verdict.

    Implementations return a **raw verdict dict** (the model's unvalidated
    output) shaped like::

        {
            "value": "confirmed_threat",           # VerdictValue
            "confidence": 0.87,                     # numeric, clamped to [0,1]
            "threatCategory": "session-hijack",     # ThreatCategory
        }

    The node performs all schema validation, clamping, and malformed
    classification; the model is never trusted to return well-formed output. An
    implementation MAY raise to signal an error (converted by the node into a
    forced ``uncertain`` verdict, R9.4) or raise :class:`ToolTimeout`.
    """

    def propose_verdict(
        self, request: VerdictRequest
    ) -> Mapping[str, Any]:  # pragma: no cover - protocol
        ...


class StaticVerdictModel:
    """Deterministic :class:`VerdictModel` double for the graph and tests.

    Returns the canned ``raw_verdict`` verbatim, or raises ``error`` if one was
    supplied (to exercise the model-error / timeout paths). ``raw_verdict`` is
    intentionally typed loosely so a test can pass malformed shapes (a
    non-mapping, missing keys, out-of-set value/category, missing/non-numeric
    confidence, etc.) to drive every guard branch.
    """

    def __init__(
        self,
        raw_verdict: Any = None,
        *,
        error: Optional[BaseException] = None,
    ) -> None:
        self._raw = raw_verdict
        self._error = error

    def propose_verdict(self, request: VerdictRequest) -> Any:
        if self._error is not None:
            raise self._error
        return self._raw


@dataclass(frozen=True)
class SynthesizeVerdictResult:
    """The outcome of the ``synthesize_verdict`` node.

    Attributes:
        state: The updated :class:`TriageState`. ``state.verdict`` is always set
            after this node runs; ``state.escalated`` is set on the fail-open /
            uncertain path (R9.4, R9.6).
        route: The routing literal for the next node - :data:`ROUTE_DRAFT_ACTION`
            for a confident, well-formed verdict, else :data:`ROUTE_ESCALATE`.
        verdict: The recorded verdict (convenience mirror of ``state.verdict``).
        malformed: True iff fail-open handling fired (schema failure, unparseable,
            or malformed classification) (R9.4).
        reason: On the fail-open path, a short human-readable reason; ``None``
            otherwise.
    """

    state: TriageState
    route: str
    verdict: Verdict
    malformed: bool = False
    reason: Optional[str] = None

    @property
    def escalated(self) -> bool:
        """True iff the node routed to escalation (R9.4, R9.6)."""
        return self.route == ROUTE_ESCALATE


def synthesize_verdict(
    state: TriageState,
    model: VerdictModel,
    *,
    config: Optional[SidecarConfig] = None,
    runner: Optional[TimeoutRunner] = None,
) -> SynthesizeVerdictResult:
    """Synthesize, validate, clamp, and malformed-check the verdict (R9).

    Args:
        state: The investigation state after probing; mutated in place with the
            recorded verdict and (on the fail-open path) ``escalated`` + a gap.
        model: The injectable language model producing the raw verdict.
        config: Sidecar configuration (defaults to the process config); supplies
            the per-tool wall-clock timeout.
        runner: The timeout runner enforcing the model deadline (defaults to
            :class:`ThreadTimeoutRunner`); inject a double in tests.

    Returns:
        A :class:`SynthesizeVerdictResult` whose ``route`` is
        :data:`ROUTE_DRAFT_ACTION` for a confident, well-formed, non-uncertain
        verdict, else :data:`ROUTE_ESCALATE`.
    """
    cfg = config or get_config()
    run = runner or ThreadTimeoutRunner()

    # Stage 0: obtain the raw model output under the per-tool timeout. A
    # timeout, error, or missing/unparseable output fails open to a human.
    raw, invoke_reason = _invoke_model(state, model, cfg, run)
    if invoke_reason is not None:
        return _fail_open(state, invoke_reason)

    # Stage 1 (independent, R9.3): classify malformed on the raw output directly,
    # so malformed handling fires even if schema validation would pass.
    malformed_reason = _classify_malformed(raw)
    if malformed_reason is not None:
        return _fail_open(state, malformed_reason)

    # Stage 2 (R9.2): schema-validate + clamp confidence into [0.0, 1.0].
    verdict, schema_reason = _validate_schema(raw)
    if verdict is None:
        return _fail_open(state, schema_reason or "verdict failed schema validation")

    # A legitimately-produced ``uncertain`` verdict is well-formed (not
    # malformed) but still escalates and drafts no autonomous action (R9.6).
    if verdict.value is VerdictValue.UNCERTAIN:
        state.verdict = verdict
        state.escalated = True
        return SynthesizeVerdictResult(
            state=state,
            route=ROUTE_ESCALATE,
            verdict=verdict,
            malformed=False,
        )

    # Confident, well-formed, non-uncertain verdict -> proceed to draft_action.
    state.verdict = verdict
    return SynthesizeVerdictResult(
        state=state,
        route=ROUTE_DRAFT_ACTION,
        verdict=verdict,
        malformed=False,
    )


def route_after_synthesize_verdict(state: TriageState) -> str:
    """Re-derive the routing decision from state (for LangGraph edges).

    Routes to :data:`ROUTE_ESCALATE` iff the run has been escalated (a forced or
    legitimately-uncertain verdict), otherwise to :data:`ROUTE_DRAFT_ACTION`.
    Mirrors the ``route`` value returned by :func:`synthesize_verdict`.
    """
    return ROUTE_ESCALATE if state.escalated else ROUTE_DRAFT_ACTION


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _invoke_model(
    state: TriageState,
    model: VerdictModel,
    config: SidecarConfig,
    runner: TimeoutRunner,
) -> tuple[Any, Optional[str]]:
    """Call the model under the per-tool timeout.

    Returns ``(raw, None)`` on success, or ``(None, reason)`` when the model
    timed out, errored, or returned no output. Never raises.
    """
    request = VerdictRequest.from_state(state)
    timeout = config.per_tool_timeout_seconds

    try:
        raw = runner.run(lambda: model.propose_verdict(request), timeout)
    except ToolTimeout:
        return None, (
            "verdict synthesis exceeded the configured wall-clock timeout of "
            f"{timeout}s"
        )
    except Exception as exc:  # noqa: BLE001 - any model error -> fail open
        return None, f"language model error: {exc!r}"

    if raw is None:
        return None, "language model returned no verdict"

    return raw, None


def _classify_malformed(raw: Any) -> Optional[str]:
    """Independently classify the raw verdict as malformed (R9.3).

    Returns a short reason string when malformed, else ``None``. Runs on the raw
    output directly and independently of schema validation so it fires even for
    outputs that would otherwise pass the schema.

    Malformed when:
      * the output is not a mapping (unparseable shape);
      * any required field (``value``, ``confidence``, ``threatCategory``) is
        absent;
      * ``value`` is outside the permitted :class:`VerdictValue` set;
      * ``threatCategory`` is outside the permitted :class:`ThreatCategory` set;
      * ``confidence`` is missing or non-numeric.
    """
    if not isinstance(raw, Mapping):
        return "malformed verdict: output is not a mapping/object"

    # Required-field presence (R9.3).
    missing = [field for field in _REQUIRED_FIELDS if field not in raw]
    if missing:
        return f"malformed verdict: required field(s) absent: {', '.join(missing)}"

    # verdict value in permitted set (R9.3).
    if not _is_permitted_verdict_value(raw.get("value")):
        return (
            "malformed verdict: value outside the permitted set "
            f"(got {raw.get('value')!r})"
        )

    # threatCategory in permitted set (R9.3).
    if not _is_permitted_threat_category(raw.get("threatCategory")):
        return (
            "malformed verdict: threatCategory outside the permitted set "
            f"(got {raw.get('threatCategory')!r})"
        )

    # confidence present and numeric (R9.3).
    if not _is_numeric(raw.get("confidence")):
        return (
            "malformed verdict: confidence is missing or non-numeric "
            f"(got {raw.get('confidence')!r})"
        )

    return None


def _validate_schema(raw: Mapping[str, Any]) -> tuple[Optional[Verdict], Optional[str]]:
    """Validate the raw output against the Verdict schema, clamping confidence.

    Returns ``(verdict, None)`` on success or ``(None, reason)`` on failure. The
    :class:`Verdict` model clamps ``confidence`` into ``[0.0, 1.0]`` (R9.2).
    """
    try:
        verdict = Verdict(
            value=raw["value"],
            confidence=raw["confidence"],
            threatCategory=raw["threatCategory"],
            malformedRejected=False,
        )
    except ValidationError as exc:
        return None, f"verdict failed schema validation: {_summarize(exc)}"
    return verdict, None


def _fail_open(state: TriageState, reason: str) -> SynthesizeVerdictResult:
    """Force ``uncertain`` + ``confidence 0.0`` and escalate to a human (R9.4).

    Records a gap naming the failure, sets the forced verdict with
    ``malformedRejected=True``, marks the run escalated, and routes to
    :data:`ROUTE_ESCALATE`. Drafts no autonomous action (R9.6).
    """
    state.gaps.append(
        Gap(
            stage=STAGE_VERDICT,
            element=f"verdict[alertId={state.alertId}]",
            reason=reason,
        )
    )
    verdict = Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=0.0,
        threatCategory=MALFORMED_FALLBACK_THREAT_CATEGORY,
        malformedRejected=True,
    )
    state.verdict = verdict
    state.escalated = True

    return SynthesizeVerdictResult(
        state=state,
        route=ROUTE_ESCALATE,
        verdict=verdict,
        malformed=True,
        reason=reason,
    )


def _is_permitted_verdict_value(value: Any) -> bool:
    """True iff ``value`` maps to exactly one permitted :class:`VerdictValue`."""
    if isinstance(value, VerdictValue):
        return True
    if isinstance(value, str):
        try:
            VerdictValue(value)
            return True
        except ValueError:
            return False
    return False


def _is_permitted_threat_category(value: Any) -> bool:
    """True iff ``value`` maps to exactly one permitted :class:`ThreatCategory`."""
    if isinstance(value, ThreatCategory):
        return True
    if isinstance(value, str):
        try:
            ThreatCategory(value)
            return True
        except ValueError:
            return False
    return False


def _is_numeric(value: Any) -> bool:
    """True iff ``value`` is a real number (bool is rejected as non-numeric).

    ``bool`` is a subclass of ``int`` in Python but is not a meaningful
    confidence, so it is treated as non-numeric/malformed.
    """
    if isinstance(value, bool):
        return False
    return isinstance(value, numbers.Real)


def _summarize(exc: ValidationError) -> str:
    """Condense a pydantic error into a short, human-readable reason string."""
    parts = []
    for err in exc.errors():
        loc = ".".join(str(p) for p in err.get("loc", ())) or "value"
        parts.append(f"{loc}: {err.get('msg', 'invalid')}")
    return "; ".join(parts) if parts else "validation failed"


__all__ = [
    "NODE_SYNTHESIZE_VERDICT",
    "ROUTE_DRAFT_ACTION",
    "ROUTE_ESCALATE",
    "STAGE_VERDICT",
    "MALFORMED_FALLBACK_THREAT_CATEGORY",
    "VerdictRequest",
    "VerdictModel",
    "StaticVerdictModel",
    "SynthesizeVerdictResult",
    "synthesize_verdict",
    "route_after_synthesize_verdict",
]
