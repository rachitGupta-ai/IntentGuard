"""The read-only Read_Tool registry: exactly five tools, no write tools.

This module is the *structural* read-only guarantee of the sidecar (R1.2,
R12.4). It registers exactly five read-only tools and nothing capable of a
write, block, or enforcement operation:

    get_session_history   query_audit_history   get_related_alerts
    get_actor_profile     get_exfil_correlations

Every tool:

  * runs its wrapped backend call under the configured per-tool wall-clock
    timeout (30s, R5.1/R5.2), via an injectable :class:`TimeoutRunner`;
  * tags every returned element with its Audit_History record id, producing an
    ``Evidence_Item`` whose ``auditRecordId`` is set and whose
    ``sourceContentUntrusted`` is ``True`` — all fetched content is
    Untrusted_Content (R5.4, R6.4, R10.3, R12.1);
  * caps correlation results at the configured maximum (<=100) for
    ``get_related_alerts`` / ``get_exfil_correlations`` (R6.1, R6.2);
  * for ``query_audit_history``, scopes the query to the alert's actor and to a
    window not exceeding 30 days ending at the alert timestamp (R6.3); and
  * converts no-data, an error, or a timeout into a recorded :class:`Gap`
    (stage/element/reason) instead of raising, so the investigation continues
    (R5.3, R6.5, R8.8).

The registry exposes :meth:`ReadToolRegistry.tool_names` and
:meth:`ReadToolRegistry.has_tool` so the read-only-enforcement layer (task 6.2)
can decide, before execution, whether a requested tool is in-set.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Optional, Sequence

from sidecar.config import SidecarConfig, get_config
from sidecar.models import Evidence_Item, Gap
from sidecar.tools.backend import EngineRecord, ReadOnlyEngineBackend
from sidecar.tools.timeout import ThreadTimeoutRunner, TimeoutRunner, ToolTimeout

# Stage labels (aligned with the Gap.stage vocabulary used across the graph).
_STAGE_CONTEXT = "context"
_STAGE_CORRELATE = "correlate"

# The five read-only tool names, frozen in canonical order. This tuple is the
# single source of truth for "which tools exist"; enforcement checks compare
# against it.
READ_TOOL_NAMES: tuple[str, ...] = (
    "get_session_history",
    "get_actor_profile",
    "query_audit_history",
    "get_related_alerts",
    "get_exfil_correlations",
)

_MS_PER_SECOND = 1000


@dataclass(frozen=True)
class ToolResult:
    """The outcome of a single Read_Tool invocation.

    ``evidence`` holds the record-id-tagged, untrusted-classified facts that
    were successfully bound. ``gap`` is set when the call returned no data,
    errored, timed out, or when some/all returned records could not be bound to
    an Audit_History record id. ``evidence`` and ``gap`` may both be present
    (a partial result: keep what was gathered, record the shortfall).
    """

    tool_name: str
    stage: str
    evidence: tuple[Evidence_Item, ...] = ()
    gap: Optional[Gap] = None

    @property
    def ok(self) -> bool:
        """True when the call produced evidence and recorded no shortfall."""
        return self.gap is None and bool(self.evidence)


class ReadTool:
    """A single read-only tool wrapping one engine data source.

    The shared behaviour (timeout enforcement, record-id tagging, untrusted
    classification, result capping, gap-on-failure) lives here; per-tool
    specifics (which backend method to call and how to shape its arguments)
    are supplied as a ``fetch`` closure.
    """

    def __init__(
        self,
        *,
        name: str,
        stage: str,
        kind: str,
        fetch: Callable[..., Sequence[EngineRecord]],
        config: SidecarConfig,
        runner: TimeoutRunner,
        result_cap: Optional[int] = None,
    ) -> None:
        self.name = name
        self.stage = stage
        self.kind = kind
        self._fetch = fetch
        self._config = config
        self._runner = runner
        self._result_cap = result_cap

    def invoke(self, **kwargs) -> ToolResult:
        """Run the wrapped fetch under timeout and return a :class:`ToolResult`.

        Never raises for backend failure: no-data, error, and timeout are all
        converted into a recorded gap so the investigation continues.
        """
        timeout = self._config.per_tool_timeout_seconds
        try:
            raw = self._runner.run(lambda: self._fetch(**kwargs), timeout)
        except ToolTimeout:
            return self._gap_only(
                f"exceeded per-tool wall-clock timeout of {timeout}s"
            )
        except Exception as exc:  # noqa: BLE001 - any engine error -> gap
            return self._gap_only(f"read tool error: {exc!r}")

        records = list(raw or [])
        if not records:
            return self._gap_only("no data returned")

        # Cap correlation results (R6.1, R6.2). Applied before binding so the
        # bound evidence never exceeds the cap.
        if self._result_cap is not None and len(records) > self._result_cap:
            records = records[: self._result_cap]

        evidence: list[Evidence_Item] = []
        unbound = 0
        for record in records:
            if record.auditRecordId:
                evidence.append(self._to_evidence(record))
            else:
                unbound += 1

        if not evidence:
            # Every returned record was unbindable.
            return self._gap_only(
                "no records could be bound to an Audit_History record id"
            )

        gap: Optional[Gap] = None
        if unbound:
            # Partial: keep the bound evidence, record the unbindable shortfall.
            gap = Gap(
                stage=self.stage,
                element=self.name,
                reason=(
                    f"{unbound} record(s) lacked an Audit_History record id and "
                    "were excluded"
                ),
            )
        return ToolResult(
            tool_name=self.name,
            stage=self.stage,
            evidence=tuple(evidence),
            gap=gap,
        )

    def _to_evidence(self, record: EngineRecord) -> Evidence_Item:
        summary = record.summary.strip() if record.summary else ""
        if not summary:
            summary = f"{self.name} record {record.auditRecordId}"
        return Evidence_Item(
            auditRecordId=record.auditRecordId,
            kind=self.kind,
            summary=summary,
            sourceContentUntrusted=True,  # Untrusted_Content on receipt (R12.1).
        )

    def _gap_only(self, reason: str) -> ToolResult:
        return ToolResult(
            tool_name=self.name,
            stage=self.stage,
            evidence=(),
            gap=Gap(stage=self.stage, element=self.name, reason=reason),
        )


class ReadToolRegistry:
    """The registry of exactly five read-only tools — and nothing else.

    Construct with an injected :class:`ReadOnlyEngineBackend` (real engine in
    production, deterministic double in tests). The registry never contains a
    write/block/enforcement tool; :meth:`tool_names` and :meth:`has_tool` let
    the enforcement layer verify a requested tool is in-set before execution.
    """

    def __init__(
        self,
        backend: ReadOnlyEngineBackend,
        *,
        config: Optional[SidecarConfig] = None,
        runner: Optional[TimeoutRunner] = None,
    ) -> None:
        self._backend = backend
        self._config = config or get_config()
        self._runner = runner or ThreadTimeoutRunner()
        self._tools: dict[str, ReadTool] = {}
        self._build_tools()
        self._verify_read_only()

    # --- construction -----------------------------------------------------
    def _build_tools(self) -> None:
        cap = self._config.correlation_result_cap

        def _register(tool: ReadTool) -> None:
            self._tools[tool.name] = tool

        _register(
            ReadTool(
                name="get_session_history",
                stage=_STAGE_CONTEXT,
                kind="context",
                fetch=lambda alert_id: self._backend.get_session_history(alert_id),
                config=self._config,
                runner=self._runner,
            )
        )
        _register(
            ReadTool(
                name="get_actor_profile",
                stage=_STAGE_CONTEXT,
                kind="context",
                fetch=lambda actor_id: self._backend.get_actor_profile(actor_id),
                config=self._config,
                runner=self._runner,
            )
        )
        _register(
            ReadTool(
                name="query_audit_history",
                stage=_STAGE_CORRELATE,
                kind="correlation",
                fetch=self._fetch_audit_history,
                config=self._config,
                runner=self._runner,
            )
        )
        _register(
            ReadTool(
                name="get_related_alerts",
                stage=_STAGE_CORRELATE,
                kind="correlation",
                fetch=lambda actor_id, session_id: self._backend.get_related_alerts(
                    actor_id, session_id
                ),
                config=self._config,
                runner=self._runner,
                result_cap=cap,
            )
        )
        _register(
            ReadTool(
                name="get_exfil_correlations",
                stage=_STAGE_CORRELATE,
                kind="correlation",
                fetch=lambda actor_id, session_id: self._backend.get_exfil_correlations(
                    actor_id, session_id
                ),
                config=self._config,
                runner=self._runner,
                result_cap=cap,
            )
        )

    def _verify_read_only(self) -> None:
        """Guard: the registry must hold exactly the five read-only tools."""
        registered = frozenset(self._tools)
        expected = frozenset(READ_TOOL_NAMES)
        if registered != expected:
            unexpected = registered - expected
            missing = expected - registered
            raise RuntimeError(
                "read-only tool registry integrity violated: "
                f"unexpected={sorted(unexpected)} missing={sorted(missing)}"
            )

    # --- audit-history window scoping (R6.3) ------------------------------
    def _fetch_audit_history(
        self,
        actor_id: str,
        alert_timestamp_ms: int,
        requested_from_ms: Optional[int] = None,
    ) -> Sequence[EngineRecord]:
        """Fetch audit history clamped to the actor and a <=30-day window.

        The window always ends at the alert timestamp and never spans more than
        the configured maximum (30 days). A caller-requested start is clamped
        into ``[alert_ts - max_window, alert_ts]``; an invalid or absent start
        falls back to the full permitted window.
        """
        to_ms = alert_timestamp_ms
        max_window_ms = self._config.correlation_window_max_seconds * _MS_PER_SECOND
        earliest_ms = alert_timestamp_ms - max_window_ms
        if requested_from_ms is None:
            from_ms = earliest_ms
        else:
            from_ms = max(int(requested_from_ms), earliest_ms)
            if from_ms > to_ms:
                from_ms = earliest_ms
        return self._backend.query_audit_history(actor_id, from_ms, to_ms)

    # --- enumeration / lookup (for enforcement, task 6.2) -----------------
    def tool_names(self) -> tuple[str, ...]:
        """The names of the registered read-only tools, in canonical order."""
        return READ_TOOL_NAMES

    def has_tool(self, name: str) -> bool:
        """True iff ``name`` is one of the registered read-only tools."""
        return name in self._tools

    def get(self, name: str) -> ReadTool:
        """Return the registered tool named ``name`` (KeyError if out-of-set)."""
        return self._tools[name]

    # --- typed convenience wrappers ---------------------------------------
    def get_session_history(self, alert_id: str) -> ToolResult:
        return self._tools["get_session_history"].invoke(alert_id=alert_id)

    def get_actor_profile(self, actor_id: str) -> ToolResult:
        return self._tools["get_actor_profile"].invoke(actor_id=actor_id)

    def query_audit_history(
        self,
        actor_id: str,
        alert_timestamp_ms: int,
        requested_from_ms: Optional[int] = None,
    ) -> ToolResult:
        return self._tools["query_audit_history"].invoke(
            actor_id=actor_id,
            alert_timestamp_ms=alert_timestamp_ms,
            requested_from_ms=requested_from_ms,
        )

    def get_related_alerts(self, actor_id: str, session_id: str) -> ToolResult:
        return self._tools["get_related_alerts"].invoke(
            actor_id=actor_id, session_id=session_id
        )

    def get_exfil_correlations(self, actor_id: str, session_id: str) -> ToolResult:
        return self._tools["get_exfil_correlations"].invoke(
            actor_id=actor_id, session_id=session_id
        )


def build_registry(
    backend: ReadOnlyEngineBackend,
    *,
    config: Optional[SidecarConfig] = None,
    runner: Optional[TimeoutRunner] = None,
) -> ReadToolRegistry:
    """Convenience factory for a :class:`ReadToolRegistry`."""
    return ReadToolRegistry(backend, config=config, runner=runner)


__all__ = [
    "READ_TOOL_NAMES",
    "ToolResult",
    "ReadTool",
    "ReadToolRegistry",
    "build_registry",
]
