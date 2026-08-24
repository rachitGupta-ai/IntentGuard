"""The ``form_hypotheses`` Investigation_Graph node (task 12.1, R7).

When correlation completes, this node asks the language model for candidate
explanations of the alert, then validates each one into a well-formed
:class:`Hypothesis` (R7.1). A retained hypothesis must:

  * map to **exactly one** permitted :class:`ThreatCategory` (R7.4); one that
    cannot be mapped is discarded and the mapping failure recorded as a gap
    (R7.5); and
  * carry **at least one** supporting :class:`Evidence_Item`, each bound to the
    ``Audit_History`` record id it was derived from (R7.6, R7.7); one with no
    bindable supporting evidence is discarded and the absence recorded as a gap
    (R7.8).

The model call is bounded by the configured per-tool wall-clock timeout (30s,
R7.2). A timeout, an empty response, an unparseable response, or a model error
each records a gap and lets the investigation continue rather than halting
(R7.3, R7.9). Between 1 and 10 well-formed hypotheses are retained; any beyond
the tenth are dropped with a recorded gap (R7.1).

Testability
-----------
All LLM I/O sits behind the injectable :class:`HypothesisModel` protocol so the
graph and its tests run without external dependencies. :class:`StaticHypothesisModel`
is the deterministic double: it returns canned raw hypotheses (or raises a
supplied error) so tests can exercise the malformed / unmappable / no-evidence /
empty / error / timeout paths. The per-tool timeout is enforced through the same
injectable :class:`TimeoutRunner` the read-only tool layer uses, so a test can
force the timeout path deterministically.

Routing (matching the design's conditional edges)
--------------------------------------------------
  * ``form_hypotheses --> probe`` when >=1 unresolved hypothesis is retained
    (R7, R8); else
  * ``form_hypotheses --> synthesize_verdict`` when no valid hypotheses remain
    (all discarded / gaps) (R7.9).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Protocol, Sequence, runtime_checkable

from pydantic import ValidationError

from sidecar.config import SidecarConfig, get_config
from sidecar.models import Evidence_Item, Gap, Hypothesis, ThreatCategory, TriageState
from sidecar.tools.timeout import (
    ThreadTimeoutRunner,
    TimeoutRunner,
    ToolTimeout,
)
from sidecar.triage.evidence import RawElement, bind_evidence
from sidecar.triage.untrusted import (
    AnalysisInput,
    prepare_analysis_input,
    record_injection_attempts,
)

# Stage label recorded on every gap this node produces (aligned with the
# Gap.stage vocabulary used across the graph).
STAGE_HYPOTHESES = "hypotheses"

# The design's conditional routing targets out of ``form_hypotheses``.
ROUTE_PROBE = "probe"
ROUTE_SYNTHESIZE_VERDICT = "synthesize_verdict"

# R7.1: between 1 and 10 hypotheses.
MAX_HYPOTHESES = 10

# Default kind assigned to a supporting Evidence_Item when the model omits one.
_DEFAULT_SUPPORT_KIND = "hypothesis-support"


@dataclass(frozen=True)
class HypothesisRequest:
    """The read-only view of investigation state handed to the model.

    All fields are analysis data derived from already-gathered, record-id-bound
    evidence. Content is treated strictly as ``Untrusted_Content`` for analysis
    (R12.1/R12.2); the model is asked only to *propose* hypotheses, never to act.
    """

    alertId: str
    triggerType: Optional[str] = None
    context: tuple[Evidence_Item, ...] = ()
    correlations: tuple[Evidence_Item, ...] = ()
    # Analysis-only, prompt-hardened rendering of the untrusted context and
    # correlation content (R12.2). A real model adapter sends this text rather than
    # the raw summaries so embedded directives are structurally marked as data.
    analysisText: Optional[str] = None

    @classmethod
    def from_state(cls, state: TriageState) -> "HypothesisRequest":
        untrusted = list(state.context) + list(state.correlations)
        analysis: AnalysisInput = prepare_analysis_input(untrusted)
        return cls(
            alertId=state.alertId,
            triggerType=state.triggerType.value if state.triggerType else None,
            context=tuple(state.context),
            correlations=tuple(state.correlations),
            analysisText=analysis.text,
        )


@runtime_checkable
class HypothesisModel(Protocol):
    """The injectable language-model interface used to form hypotheses.

    Implementations return an iterable of **raw hypothesis dicts** (the model's
    unvalidated output). Each raw hypothesis is a mapping shaped like::

        {
            "id": "h1",                       # optional; generated if absent
            "statement": "actor session was hijacked",
            "threatCategory": "session-hijack",
            "supportingEvidence": [
                {"kind": "context", "summary": "...", "auditRecordId": "evt-1"},
                ...
            ],
        }

    The node performs all validation/mapping; the model is never trusted to
    return well-formed output. An implementation MAY raise to signal an error
    (converted by the node into a recorded gap, R7.9) or raise
    :class:`ToolTimeout` (R7.3).
    """

    def propose_hypotheses(
        self, request: HypothesisRequest
    ) -> Sequence[Mapping[str, Any]]:  # pragma: no cover - protocol
        ...


class StaticHypothesisModel:
    """Deterministic :class:`HypothesisModel` double for the graph and tests.

    Returns the canned ``raw_hypotheses`` verbatim, or raises ``error`` if one
    was supplied (to exercise the model-error / timeout paths). ``raw_hypotheses``
    is intentionally typed loosely so a test can pass malformed shapes (a
    non-list, non-mapping items, missing keys, etc.) to drive the unparseable /
    discard branches.
    """

    def __init__(
        self,
        raw_hypotheses: Any = None,
        *,
        error: Optional[BaseException] = None,
    ) -> None:
        self._raw = raw_hypotheses
        self._error = error

    def propose_hypotheses(self, request: HypothesisRequest) -> Any:
        if self._error is not None:
            raise self._error
        # ``None`` models an empty response; anything else is returned as-is so
        # the node's parser sees exactly what a real model might emit.
        return [] if self._raw is None else self._raw


@dataclass(frozen=True)
class FormHypothesesResult:
    """The outcome of the ``form_hypotheses`` node.

    ``state`` is the same (mutated) :class:`TriageState` with retained
    hypotheses appended to ``state.hypotheses`` and every discard/failure
    recorded in ``state.gaps``. ``route`` is the next graph edge.
    """

    state: TriageState
    route: str
    retained: tuple[Hypothesis, ...] = ()
    gaps_recorded: tuple[Gap, ...] = ()

    @property
    def routed_to_probe(self) -> bool:
        """True iff at least one unresolved hypothesis was retained (R7/R8)."""
        return self.route == ROUTE_PROBE


def form_hypotheses(
    state: TriageState,
    model: HypothesisModel,
    *,
    config: Optional[SidecarConfig] = None,
    runner: Optional[TimeoutRunner] = None,
) -> FormHypothesesResult:
    """Form, validate, and retain 1-10 threat-mapped hypotheses (R7).

    Args:
        state: The investigation state after correlation; mutated in place with
            retained hypotheses and recorded gaps, then returned in the result.
        model: The injectable language model producing raw hypotheses.
        config: Sidecar configuration (defaults to the process config); supplies
            the per-tool wall-clock timeout (R7.2).
        runner: The timeout runner enforcing the model deadline (defaults to
            :class:`ThreadTimeoutRunner`); inject a double in tests.

    Returns:
        A :class:`FormHypothesesResult` whose ``route`` is ``probe`` when >=1
        unresolved hypothesis was retained, else ``synthesize_verdict`` (R7.9).
    """
    cfg = config or get_config()
    run = runner or ThreadTimeoutRunner()

    # R12.1-12.3: before handing any untrusted context/correlation content to the
    # model, scan it for embedded prompt-injection directives. Each piece of content
    # carrying one is recorded as an injection_attempt Evidence_Item in state.evidence
    # (bound to its record id) and then treated as ordinary analysis data. This
    # records the attempt without changing behaviour or taking any out-of-set action.
    record_injection_attempts(state, list(state.context) + list(state.correlations))

    new_gaps: list[Gap] = []

    raw_hypotheses = _invoke_model(state, model, cfg, run, new_gaps)

    retained: list[Hypothesis] = []
    if raw_hypotheses is not None:
        for index, raw in enumerate(raw_hypotheses):
            hypothesis = _validate_raw_hypothesis(raw, index, new_gaps)
            if hypothesis is not None:
                retained.append(hypothesis)

    # R7.1: cap the retained set at 10; record the truncation as a gap.
    if len(retained) > MAX_HYPOTHESES:
        dropped = len(retained) - MAX_HYPOTHESES
        retained = retained[:MAX_HYPOTHESES]
        new_gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis set",
                reason=(
                    f"more than {MAX_HYPOTHESES} well-formed hypotheses formed; "
                    f"{dropped} beyond the cap were discarded"
                ),
            )
        )

    # Commit to state (assignment re-validates via validate_assignment=True).
    state.hypotheses = list(state.hypotheses) + retained
    state.gaps = list(state.gaps) + new_gaps

    # Route: probe while any unresolved hypothesis remains, else synthesize.
    has_unresolved = any(not h.resolved for h in retained)
    route = ROUTE_PROBE if has_unresolved else ROUTE_SYNTHESIZE_VERDICT

    return FormHypothesesResult(
        state=state,
        route=route,
        retained=tuple(retained),
        gaps_recorded=tuple(new_gaps),
    )


def _invoke_model(
    state: TriageState,
    model: HypothesisModel,
    config: SidecarConfig,
    runner: TimeoutRunner,
    gaps: list[Gap],
) -> Optional[list[Any]]:
    """Call the model under the per-tool timeout and normalize its output.

    Returns a list of raw hypothesis items, or ``None`` when the model timed
    out, errored, returned nothing, or returned an unparseable (non-iterable)
    response. Every such failure appends a gap (R7.3, R7.9). Never raises.
    """
    request = HypothesisRequest.from_state(state)
    timeout = config.per_tool_timeout_seconds

    try:
        raw = runner.run(lambda: model.propose_hypotheses(request), timeout)
    except ToolTimeout:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason=(
                    "hypothesis formation exceeded the configured wall-clock "
                    f"timeout of {timeout}s"
                ),
            )
        )
        return None
    except Exception as exc:  # noqa: BLE001 - any model error -> recorded gap
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason=f"language model error: {exc!r}",
            )
        )
        return None

    if raw is None:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason="language model returned no hypotheses",
            )
        )
        return None

    # A string is iterable but is not a valid hypothesis list; treat it (and any
    # non-iterable) as unparseable output.
    if isinstance(raw, (str, bytes, Mapping)):
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason="language model returned an unparseable response",
            )
        )
        return None

    try:
        items = list(raw)
    except TypeError:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason="language model returned an unparseable response",
            )
        )
        return None

    if not items:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element="hypothesis formation",
                reason="language model returned no hypotheses",
            )
        )
        return None

    return items


def _validate_raw_hypothesis(
    raw: Any, index: int, gaps: list[Gap]
) -> Optional[Hypothesis]:
    """Validate one raw hypothesis into a :class:`Hypothesis`, or discard it.

    Returns the retained hypothesis, or ``None`` when it is discarded; a discard
    always appends a gap naming the element and the reason (R7.5, R7.8, R7.9).
    Any bindable-but-unbound supporting evidence of a *retained* hypothesis is
    also recorded as a gap (traceability shortfall).
    """
    if not isinstance(raw, Mapping):
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element=f"hypothesis[{index}]",
                reason="malformed hypothesis: not a mapping/object",
            )
        )
        return None

    label = _hypothesis_label(raw, index)

    # R7.4/R7.5: map to exactly one permitted Threat_Category, else discard.
    category = _map_threat_category(raw.get("threatCategory"))
    if category is None:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element=label,
                reason=(
                    "hypothesis could not be mapped to exactly one permitted "
                    f"Threat_Category (got {raw.get('threatCategory')!r})"
                ),
            )
        )
        return None

    # R7.6/R7.8: require >=1 bindable supporting Evidence_Item.
    raw_support = _coerce_support_elements(raw.get("supportingEvidence"))
    binding = bind_evidence(raw_support, stage=STAGE_HYPOTHESES)
    if not binding.bound:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element=label,
                reason="hypothesis has no supporting Evidence_Item bound to a record id",
            )
        )
        return None

    try:
        hypothesis = Hypothesis(
            id=str(raw.get("id") or label),
            statement=str(raw.get("statement") or "").strip(),
            threatCategory=category,
            supportingEvidence=binding.bound,
            resolved=False,
        )
    except ValidationError as exc:
        gaps.append(
            Gap(
                stage=STAGE_HYPOTHESES,
                element=label,
                reason=f"malformed hypothesis: {_summarize_validation_error(exc)}",
            )
        )
        return None

    # Retained: still surface any supporting elements that could not be bound so
    # the investigation state is honest about the traceability shortfall.
    gaps.extend(binding.gaps)
    return hypothesis


def _hypothesis_label(raw: Mapping[str, Any], index: int) -> str:
    """Pick a non-empty identifier to name a hypothesis in a gap entry."""
    for candidate in (raw.get("id"), raw.get("statement")):
        if candidate and str(candidate).strip():
            return str(candidate)
    return f"hypothesis[{index}]"


def _map_threat_category(raw: Any) -> Optional[ThreatCategory]:
    """Map a raw category value to exactly one :class:`ThreatCategory` (R7.4).

    Accepts an already-parsed :class:`ThreatCategory` or its string value.
    Returns ``None`` when the value is missing or not one of the five permitted
    labels (an unmappable hypothesis is discarded, R7.5).
    """
    if isinstance(raw, ThreatCategory):
        return raw
    if isinstance(raw, str):
        try:
            return ThreatCategory(raw.strip())
        except ValueError:
            return None
    return None


def _coerce_support_elements(raw_support: Any) -> list[RawElement]:
    """Coerce the model's ``supportingEvidence`` into :class:`RawElement` s.

    Non-iterable or missing support yields an empty list (the hypothesis is then
    discarded for lack of evidence). Individual non-mapping entries are skipped.
    """
    if raw_support is None or isinstance(raw_support, (str, bytes, Mapping)):
        # A mapping here would be a single-item shape we do not accept; treat
        # anything that is not a proper sequence of mappings as no support.
        if isinstance(raw_support, Mapping):
            raw_support = [raw_support]
        else:
            return []

    elements: list[RawElement] = []
    try:
        iterator = iter(raw_support)
    except TypeError:
        return []

    for item in iterator:
        if isinstance(item, RawElement):
            elements.append(item)
        elif isinstance(item, Mapping):
            elements.append(
                RawElement(
                    kind=str(item.get("kind") or _DEFAULT_SUPPORT_KIND),
                    summary=str(item.get("summary") or ""),
                    auditRecordId=item.get("auditRecordId"),
                    elementId=item.get("elementId"),
                    sourceContentUntrusted=bool(
                        item.get("sourceContentUntrusted", True)
                    ),
                )
            )
        # Non-mapping entries carry nothing bindable; skip them.
    return elements


def _summarize_validation_error(exc: ValidationError) -> str:
    """Condense a pydantic error into a short, human-readable reason string."""
    parts = []
    for err in exc.errors():
        loc = ".".join(str(p) for p in err.get("loc", ())) or "value"
        parts.append(f"{loc}: {err.get('msg', 'invalid')}")
    return "; ".join(parts) if parts else "validation failed"


__all__ = [
    "STAGE_HYPOTHESES",
    "ROUTE_PROBE",
    "ROUTE_SYNTHESIZE_VERDICT",
    "MAX_HYPOTHESES",
    "HypothesisRequest",
    "HypothesisModel",
    "StaticHypothesisModel",
    "FormHypothesesResult",
    "form_hypotheses",
]
