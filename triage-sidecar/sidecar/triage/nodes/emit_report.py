"""The terminal ``emit_report`` node of the Investigation_Graph (task 17.1, R10).

``emit_report`` is the **last** node of the ``Investigation_Graph``. It turns the
accumulated :class:`~sidecar.models.TriageState` into exactly one
:class:`~sidecar.models.Triage_Report` for the Control_Tower and then routes to
the graph's end. It is reached on **both** the confident-verdict path (after
``draft_action``) and every escalation path (after the checkpoint/HITL resume),
so it must assemble a well-formed report regardless of how the investigation
concluded (R10.1).

What it assembles (R10)
-----------------------
* **verdict + confidence + threat category** (R10.1) — taken from
  ``state.verdict``. The report's ``confidence`` is a decimal in ``[0.0, 1.0]``
  (the ``Triage_Report`` model clamps it, matching R9.2/R10.1). If ``state.verdict``
  is ``None`` — an early halt that produced no verdict — the report defaults to an
  ``uncertain`` verdict with ``confidence == 0.0`` and a non-committal threat
  category, never downgrading risk (fail-open-to-human). Note that intake and
  malformed-verdict failures already set an ``uncertain`` verdict upstream; the
  ``None`` case is handled defensively here.

* **supporting evidence, each carrying its record id** (R10.1, R10.3) — the
  bound :class:`~sidecar.models.Evidence_Item` s collected across the run
  (see *Evidence aggregation* below). Every included item carries the
  ``Audit_History`` record id it was derived from.

* **exclusion entries for unbindable items** (R10.4) — any aggregated element
  that cannot be associated with an ``Audit_History`` record id is excluded from
  the report's evidence collection and recorded as an
  :class:`~sidecar.models.ExclusionEntry` naming the item and the reason. This
  reuses the shared evidence-binding helper
  (:func:`sidecar.triage.evidence.bind_evidence`) so exclusion behavior is
  identical to the graph nodes. Because upstream tool/node layers already exclude
  unbindable records, this is a defensive re-check that normally yields no
  exclusions.

* **the triggering alertId and trigger type** (R10.6) — read from ``state``
  (falling back to the validated ``state.envelope``).

* **the drafted Recommended_Action when present** (R10.2) — included verbatim
  when ``state.recommended_action`` is set, omitted otherwise. The sidecar never
  executes it (R1.4); this node only reports it.

* **a no-evidence flag with an empty evidence collection** (R10.5) — when zero
  bound evidence was collected the report carries ``evidence == []`` and
  ``noEvidenceFlag == True``.

* **the outgoing schema version** (R14.1) — stamped via a
  :class:`~sidecar.contract.MessageStamper` (or an explicit ``schema_version``),
  defaulting to the sidecar's current outgoing version.

Evidence aggregation
--------------------
The report's evidence is the union of the three bound-evidence pools threaded
through the state, in a stable order:

  1. ``state.context``      — session/profile context gathered at ``gather_context`` (R5),
  2. ``state.correlations`` — correlated signals from ``correlate`` (R6),
  3. ``state.evidence``     — probe-loop evidence and recorded refusals from ``probe`` (R8, R12.5).

Hypothesis ``supportingEvidence`` is intentionally *not* aggregated separately:
by construction those items are drawn from the same context/correlation/probe
pools, so they are already represented. Duplicates (the same fact surfaced by
more than one pool) are collapsed by ``(auditRecordId, kind, summary)`` so a
single fact is reported once, preserving first-seen order.

Routing
-------
The node returns an :class:`EmitReportResult` exposing the (unchanged) ``state``,
the assembled ``report``, and a ``next`` routing literal of :data:`ROUTE_END`
(equal to LangGraph's ``END`` sentinel), since this is the graph's terminal node.
This mirrors the constant-name + result-object convention used by the other
graph nodes. Exactly one report is produced per invocation (R1.4, R10.1).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from sidecar.config import SidecarConfig, get_config
from sidecar.contract import MessageStamper
from sidecar.models import (
    Evidence_Item,
    ThreatCategory,
    Triage_Report,
    TriageState,
    Verdict,
    VerdictValue,
)
from sidecar.triage.evidence import RawElement, bind_evidence

# Node identifier and the terminal routing literal. ``ROUTE_END`` equals the
# value of LangGraph's ``END`` sentinel so a conditional edge can map to it
# without this module importing langgraph.
EMIT_REPORT_NODE = "emit_report"
ROUTE_END = "__end__"

# The stage recorded on any exclusion/gap produced while assembling the report.
REPORT_STAGE = "report"

# Non-committal threat category for the defensive ``None``-verdict fallback.
#
# A run that reaches ``emit_report`` with no synthesized verdict cannot classify
# the threat, but ``Verdict``/``Triage_Report`` require a ``ThreatCategory``. We
# use ``off-intent-agent`` ("something is off, undetermined") together with an
# ``uncertain`` verdict and ``confidence == 0.0`` so the report asserts nothing
# about risk and never *downgrades* it (fail-open-to-human) — mirroring the
# intake node's intake-failure fallback.
DEFAULT_UNCERTAIN_THREAT_CATEGORY = ThreatCategory.OFF_INTENT_AGENT


@dataclass(frozen=True)
class EmitReportResult:
    """The outcome of the terminal ``emit_report`` node.

    Attributes:
        state: The (unchanged) :class:`TriageState` for the completed run.
        report: The single assembled :class:`Triage_Report` (R10.1).
        next: The routing literal — always :data:`ROUTE_END`.
    """

    state: TriageState
    report: Triage_Report
    next: str = ROUTE_END


def emit_report(
    state: TriageState,
    *,
    stamper: Optional[MessageStamper] = None,
    schema_version: Optional[str] = None,
    config: Optional[SidecarConfig] = None,
) -> EmitReportResult:
    """Assemble exactly one :class:`Triage_Report` from ``state`` (R10).

    Aggregates the bound evidence, excludes any unbindable item with an exclusion
    entry (R10.4), fills in the verdict/confidence/threat category (defaulting to
    an ``uncertain`` verdict when none was synthesized), the triggering
    ``alertId``/``triggerType`` (R10.6), the drafted ``Recommended_Action`` when
    present (R10.2), and a no-evidence flag when nothing was collected (R10.5),
    then stamps the outgoing schema version (R14.1).

    Args:
        state: The completed investigation state.
        stamper: Optional :class:`MessageStamper` supplying the outgoing schema
            version. Ignored when ``schema_version`` is given.
        schema_version: Optional explicit schema-version string; takes precedence
            over ``stamper``.
        config: Optional sidecar configuration used to resolve the default
            outgoing schema version when neither ``schema_version`` nor
            ``stamper`` is supplied.

    Returns:
        An :class:`EmitReportResult` carrying the assembled report and a ``next``
        of :data:`ROUTE_END`. This node does not mutate ``state``.
    """

    trigger_type = _resolve_trigger_type(state)
    version = _resolve_schema_version(
        stamper=stamper, schema_version=schema_version, config=config
    )

    verdict = _resolve_verdict(state.verdict)

    # Aggregate the three bound-evidence pools, dedup, and defensively re-check
    # bindability so any unbindable item is excluded with an exclusion entry
    # (R10.3, R10.4) — reusing the shared evidence-binding helper.
    aggregated = _aggregate_evidence(state)
    binding = bind_evidence(
        (_to_raw(item) for item in aggregated), stage=REPORT_STAGE
    )
    evidence = binding.bound

    report = Triage_Report(
        alertId=state.alertId,
        triggerType=trigger_type,
        verdict=verdict.value,
        confidence=verdict.confidence,
        threatCategory=verdict.threatCategory,
        evidence=evidence,
        excludedEvidence=binding.exclusions,
        noEvidenceFlag=not evidence,  # empty collection => flag set (R10.5)
        recommendedAction=state.recommended_action,  # included iff drafted (R10.2)
        schemaVersion=version,
    )

    return EmitReportResult(state=state, report=report, next=ROUTE_END)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _resolve_trigger_type(state: TriageState):
    """Resolve the triggering ``TriggerType`` for the report (R10.6).

    Prefers ``state.triggerType`` (set from the admitted trigger classification /
    at intake) and falls back to the validated ``state.envelope``. A run that
    reaches ``emit_report`` is always keyed by an admitted trigger, so one of the
    two is present; the explicit error guards against an upstream wiring bug.
    """

    if state.triggerType is not None:
        return state.triggerType
    if state.envelope is not None:
        return state.envelope.triggerType
    raise ValueError(
        "cannot assemble Triage_Report: no triggerType on state or envelope "
        f"(alertId={state.alertId!r})"
    )


def _resolve_verdict(verdict: Optional[Verdict]) -> Verdict:
    """Return the synthesized verdict, or a defensive ``uncertain`` default.

    An early halt can reach ``emit_report`` with ``state.verdict is None``. Per
    the fail-open-to-human guarantee that becomes an ``uncertain`` verdict with
    ``confidence == 0.0`` and a non-committal threat category (R9.4/R9.6 shape),
    never a risk downgrade.
    """

    if verdict is not None:
        return verdict
    return Verdict(
        value=VerdictValue.UNCERTAIN,
        confidence=0.0,
        threatCategory=DEFAULT_UNCERTAIN_THREAT_CATEGORY,
        malformedRejected=False,
    )


def _resolve_schema_version(
    *,
    stamper: Optional[MessageStamper],
    schema_version: Optional[str],
    config: Optional[SidecarConfig],
) -> str:
    """Resolve the outgoing schema version to stamp on the report (R14.1).

    Precedence: an explicit ``schema_version`` > the ``stamper``'s version > the
    default :class:`MessageStamper` (highest supported version in ``config``).
    """

    if schema_version is not None:
        return schema_version
    if stamper is not None:
        return stamper.schema_version
    return MessageStamper(config=config or get_config()).schema_version


def _aggregate_evidence(state: TriageState) -> list[Evidence_Item]:
    """Union the context, correlation, and probe evidence pools, deduped.

    Order is stable: context (R5), then correlations (R6), then probe/refusal
    evidence (R8, R12.5). A fact surfaced by more than one pool — identified by
    ``(auditRecordId, kind, summary)`` — is reported once, keeping its first
    occurrence.
    """

    aggregated: list[Evidence_Item] = []
    seen: set[tuple[str, str, str]] = set()
    for pool in (state.context, state.correlations, state.evidence):
        for item in pool:
            key = (item.auditRecordId, item.kind, item.summary)
            if key in seen:
                continue
            seen.add(key)
            aggregated.append(item)
    return aggregated


def _to_raw(item: Evidence_Item) -> RawElement:
    """Adapt a bound :class:`Evidence_Item` back to a :class:`RawElement`.

    Lets the report reuse :func:`bind_evidence` for the R10.4 exclusion guarantee.
    ``elementId`` is set to the record id (or the summary as a fallback name) so
    any exclusion entry names the item meaningfully.
    """

    return RawElement(
        kind=item.kind,
        summary=item.summary,
        auditRecordId=item.auditRecordId,
        elementId=item.auditRecordId or item.summary,
        sourceContentUntrusted=item.sourceContentUntrusted,
    )


__all__ = [
    "EMIT_REPORT_NODE",
    "ROUTE_END",
    "REPORT_STAGE",
    "DEFAULT_UNCERTAIN_THREAT_CATEGORY",
    "EmitReportResult",
    "emit_report",
]
