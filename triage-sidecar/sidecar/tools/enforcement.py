"""Cross-cutting read-only enforcement for the Read_Tool layer.

The registry (task 6.1) is the *structural* read-only guarantee: it holds
exactly the five read-only tools and nothing capable of a write, block, or
enforcement operation. This module adds the *behavioural* guarantee that sits
in front of the registry: a guard that inspects a requested tool name — from
**any** source, including adversarial / prompt-injected requests — and, before
anything executes, either

  * **ALLOWS** the call when the tool is in the read-only set
    (``registry.has_tool(name)`` / :data:`READ_TOOL_NAMES`), delegating to the
    registry's invocation; or
  * **DENIES** the call when the tool is out-of-set, performing **no**
    backend/tool execution (protected state is left unchanged, R1.3) and
    recording the refusal as a :class:`DeniedInvocation` and, where applicable,
    a refusal :class:`Evidence_Item` (``kind="refusal"``) naming the requested
    out-of-scope action and its source (R12.5).

Requirements
------------
R1.3   - deny out-of-set invocation, leave protected state/config unchanged,
         record the denied tool invocation.
R8.6   - all Probe_Loop tool calls are restricted to the read-only Read_Tool
         set (this guard is the single choke point that enforces it).
R8.9   - a probe request for an out-of-set tool is rejected without executing
         it and recorded in the investigation state.
R12.4  - all tool access is restricted to the read-only Read_Tool set so that
         Untrusted_Content cannot cause a write / state-changing operation.
R12.5  - any request from any source seeking an action outside the read-only
         set is refused, performs no state-changing operation, and is recorded
         (requested out-of-scope action + source) as an Evidence_Item.

Refusal Evidence_Item record id — design choice
-----------------------------------------------
An :class:`Evidence_Item` requires a **non-empty** ``auditRecordId`` (it is the
Audit_History id the fact derives from, R10.3). A refusal, however, describes an
action the sidecar declined to perform, so there is no engine-side audit record
to bind to. The refusal is therefore *self-referential*: the guard synthesises a
refusal record id (``refusal:<uuid>`` by default, via an **injectable** factory
for deterministic tests). Callers that already hold a relevant Audit_History
record id (e.g. the probe node knows the record that prompted the request) may
pass it explicitly and it is used instead of a synthesised id.

The :class:`DeniedInvocation` is the **primary, always-produced** record of a
denial; the refusal :class:`Evidence_Item` is produced "where applicable"
(on by default; a caller may suppress it, e.g. when it will attach its own
refusal evidence at a higher layer).

Public API (for the probe loop 13.1, graph guards 20.1, property test 6.3)
--------------------------------------------------------------------------
  * :class:`ReadOnlyEnforcer`      - wraps a :class:`ReadToolRegistry`; exposes
                                     :meth:`~ReadOnlyEnforcer.guarded_invoke`.
  * :class:`GuardedInvocation`     - structured result distinguishing an
                                     allowed result (``ok``/``tool_result``)
                                     from a denied result (``denial`` +
                                     optional ``refusal_evidence``).
  * :func:`build_denied_invocation`- helper building a :class:`DeniedInvocation`.
  * :func:`build_refusal_evidence` - helper building a refusal
                                     :class:`Evidence_Item`.
  * :data:`DENIAL_REASON`          - the canonical denial reason string.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Callable, Mapping, Optional

from sidecar.models import DeniedInvocation, Evidence_Item
from sidecar.tools.registry import ReadToolRegistry, ToolResult

# Canonical reason recorded on every denial. Kept as a constant so callers and
# tests can assert on it without duplicating the wording.
DENIAL_REASON = "requested tool is outside the read-only Read_Tool set"

# Kind marker for refusal evidence (aligned with Evidence_Item.kind vocabulary).
_REFUSAL_KIND = "refusal"

# Placeholder used when a request carries a blank/empty tool name. The denial
# records require a non-empty name, and every denial must be recorded (R1.3,
# R12.5), so a blank request is normalised to this label rather than dropped.
_UNNAMED_TOOL = "<unnamed>"


def _label_for(tool_name: str) -> str:
    """Return a non-empty, recordable label for a (possibly blank) tool name."""
    return tool_name if tool_name.strip() else _UNNAMED_TOOL

# A factory that produces a synthetic, self-referential refusal record id given
# the (tool_name, source) of the refused request.
RefusalRecordIdFactory = Callable[[str, str], str]


def _default_refusal_record_id(_tool_name: str, _source: str) -> str:
    """Synthesise a unique self-referential refusal record id."""
    return f"refusal:{uuid.uuid4().hex}"


def build_denied_invocation(
    requested_tool: str,
    *,
    source: str,
    reason: str = DENIAL_REASON,
) -> DeniedInvocation:
    """Build the mandatory :class:`DeniedInvocation` for an out-of-set request.

    ``requested_tool`` and ``source`` are recorded verbatim so the denial is
    auditable and attributable to its origin (R1.3, R8.9, R12.5).
    """
    return DeniedInvocation(
        requestedTool=_label_for(requested_tool),
        source=source,
        reason=reason,
    )


def build_refusal_evidence(
    requested_tool: str,
    *,
    source: str,
    audit_record_id: Optional[str] = None,
    record_id_factory: RefusalRecordIdFactory = _default_refusal_record_id,
) -> Evidence_Item:
    """Build a refusal :class:`Evidence_Item` for an out-of-scope request (R12.5).

    The evidence names the requested out-of-scope action and its source. When
    ``audit_record_id`` is supplied it is bound as the record id; otherwise a
    self-referential refusal id is synthesised via ``record_id_factory``.

    ``sourceContentUntrusted`` is ``False``: this record is generated by the
    sidecar itself (a refusal it made), not fetched Untrusted_Content.
    """
    label = _label_for(requested_tool)
    record_id = audit_record_id or record_id_factory(label, source)
    return Evidence_Item(
        auditRecordId=record_id,
        kind=_REFUSAL_KIND,
        summary=(
            f"Refused out-of-scope action: request for tool '{label}' "
            f"from source '{source}' was denied before execution "
            "(outside the read-only Read_Tool set)."
        ),
        sourceContentUntrusted=False,
    )


@dataclass(frozen=True)
class GuardedInvocation:
    """The outcome of a guarded tool request.

    Distinguishes an **allowed** result from a **denied** result:

    * Allowed  → ``allowed is True``, ``tool_result`` holds the registry's
      :class:`ToolResult`, and ``denial`` / ``refusal_evidence`` are ``None``.
    * Denied   → ``allowed is False``, ``tool_result`` is ``None``, ``denial``
      holds the mandatory :class:`DeniedInvocation`, and ``refusal_evidence``
      holds the refusal :class:`Evidence_Item` when one was produced.
    """

    requested_tool: str
    source: str
    allowed: bool
    tool_result: Optional[ToolResult] = None
    denial: Optional[DeniedInvocation] = None
    refusal_evidence: Optional[Evidence_Item] = None

    @property
    def denied(self) -> bool:
        """True when the request was denied before execution."""
        return not self.allowed

    @property
    def executed(self) -> bool:
        """True iff a backend/tool call actually ran (allowed path only)."""
        return self.allowed


class ReadOnlyEnforcer:
    """Behavioural read-only guard in front of a :class:`ReadToolRegistry`.

    Every tool request — whatever its source — passes through
    :meth:`guarded_invoke`. In-set tools are delegated to the registry; any
    out-of-set request is denied *before* execution and recorded. This is the
    single choke point relied on by the Probe_Loop (R8.6/R8.9), the graph
    guards (R1.3), and Untrusted_Content handling (R12.4/R12.5).
    """

    def __init__(
        self,
        registry: ReadToolRegistry,
        *,
        refusal_record_id_factory: RefusalRecordIdFactory = _default_refusal_record_id,
    ) -> None:
        self._registry = registry
        self._refusal_record_id_factory = refusal_record_id_factory

    # --- membership passthrough (mirrors the registry) --------------------
    def tool_names(self) -> tuple[str, ...]:
        """The names of the in-set read-only tools."""
        return self._registry.tool_names()

    def has_tool(self, name: str) -> bool:
        """True iff ``name`` is an in-set read-only tool."""
        return self._registry.has_tool(name)

    # --- the guard --------------------------------------------------------
    def guarded_invoke(
        self,
        tool_name: str,
        *,
        source: str,
        args: Optional[Mapping[str, object]] = None,
        audit_record_id: Optional[str] = None,
        record_refusal_evidence: bool = True,
    ) -> GuardedInvocation:
        """Guard a tool request, executing it only if it is in-set.

        Parameters
        ----------
        tool_name:
            The requested tool. May be anything, including an adversarial /
            injected name that is not a real tool.
        source:
            A label for where the request originated (e.g. ``"probe"``,
            ``"untrusted_content"``, ``"hypothesis"``). Recorded on the denial
            and refusal evidence for attribution (R8.9, R12.5).
        args:
            Keyword arguments forwarded to the tool when the request is allowed.
            Ignored on denial (nothing executes).
        audit_record_id:
            Optional Audit_History record id to bind onto the refusal evidence;
            when absent a self-referential refusal id is synthesised.
        record_refusal_evidence:
            When ``True`` (default), a denial also produces a refusal
            :class:`Evidence_Item`. The :class:`DeniedInvocation` is produced
            regardless.

        Returns
        -------
        GuardedInvocation
            Allowed → carries the :class:`ToolResult`. Denied → carries the
            :class:`DeniedInvocation` and (optionally) the refusal evidence,
            with **no** backend execution having occurred.
        """
        # DENY path: decide before any execution. No backend/tool call is made,
        # so protected state and IntentGuard configuration are untouched (R1.3).
        if not self._registry.has_tool(tool_name):
            denial = build_denied_invocation(tool_name, source=source)
            refusal_evidence: Optional[Evidence_Item] = None
            if record_refusal_evidence:
                refusal_evidence = build_refusal_evidence(
                    tool_name,
                    source=source,
                    audit_record_id=audit_record_id,
                    record_id_factory=self._refusal_record_id_factory,
                )
            return GuardedInvocation(
                requested_tool=tool_name,
                source=source,
                allowed=False,
                denial=denial,
                refusal_evidence=refusal_evidence,
            )

        # ALLOW path: delegate to the registry's read-only invocation.
        tool_result = self._registry.get(tool_name).invoke(**(dict(args) if args else {}))
        return GuardedInvocation(
            requested_tool=tool_name,
            source=source,
            allowed=True,
            tool_result=tool_result,
        )


__all__ = [
    "ReadOnlyEnforcer",
    "GuardedInvocation",
    "build_denied_invocation",
    "build_refusal_evidence",
    "DENIAL_REASON",
    "RefusalRecordIdFactory",
]
