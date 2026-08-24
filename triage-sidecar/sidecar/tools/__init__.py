"""Read-only Read_Tool layer for the Alert Triage Sidecar.

The tool layer is the structural read-only guarantee: exactly five read-only
tools are registered and zero write/block/enforcement tools exist (R1.2, R12.4).

Public API (task 6.1)
----------------------
Registry:
  * ``ReadToolRegistry``  : holds exactly the five read-only tools; exposes
                            ``tool_names()`` / ``has_tool()`` for the read-only
                            enforcement layer (task 6.2).
  * ``build_registry``    : factory wiring a backend (+ optional config/runner).
  * ``READ_TOOL_NAMES``   : the canonical five tool names.
  * ``ToolResult``        : per-invocation outcome (bound evidence + optional gap).
  * ``ReadTool``          : a single read-only tool wrapping one data source.

Read-only enforcement (task 6.2 — behavioural guard in front of the registry):
  * ``ReadOnlyEnforcer``  : guards every tool request; allows in-set tools,
                            denies out-of-set requests before execution and
                            records a ``DeniedInvocation`` (+ refusal
                            ``Evidence_Item``) (R1.3, R8.6, R8.9, R12.4, R12.5).
  * ``GuardedInvocation`` : structured allowed-vs-denied result.
  * ``build_denied_invocation`` / ``build_refusal_evidence`` : record helpers.

Backend interface (injectable; real engine in prod, double in tests):
  * ``ReadOnlyEngineBackend`` : the read-only data-source protocol.
  * ``EngineRecord``          : a fetched record carrying its Audit_History id.
  * ``StaticEngineBackend``   : a deterministic in-memory double for tests.

Timeout mechanism (per-tool 30s wall-clock, R5.1/R5.2/R7.2):
  * ``TimeoutRunner``       : protocol for running a call under a deadline.
  * ``ThreadTimeoutRunner`` : default thread-based enforcement.
  * ``InlineTimeoutRunner`` : deterministic inline runner for tests.
  * ``ToolTimeout``         : raised when a call overruns its deadline.
"""

from sidecar.tools.backend import (
    EngineRecord,
    ReadOnlyEngineBackend,
    StaticEngineBackend,
)
from sidecar.tools.enforcement import (
    DENIAL_REASON,
    GuardedInvocation,
    ReadOnlyEnforcer,
    RefusalRecordIdFactory,
    build_denied_invocation,
    build_refusal_evidence,
)
from sidecar.tools.registry import (
    READ_TOOL_NAMES,
    ReadTool,
    ReadToolRegistry,
    ToolResult,
    build_registry,
)
from sidecar.tools.timeout import (
    InlineTimeoutRunner,
    ThreadTimeoutRunner,
    TimeoutRunner,
    ToolTimeout,
)

__all__ = [
    # registry
    "ReadToolRegistry",
    "build_registry",
    "READ_TOOL_NAMES",
    "ToolResult",
    "ReadTool",
    # backend
    "ReadOnlyEngineBackend",
    "EngineRecord",
    "StaticEngineBackend",
    # read-only enforcement (task 6.2)
    "ReadOnlyEnforcer",
    "GuardedInvocation",
    "build_denied_invocation",
    "build_refusal_evidence",
    "DENIAL_REASON",
    "RefusalRecordIdFactory",
    # timeout
    "TimeoutRunner",
    "ThreadTimeoutRunner",
    "InlineTimeoutRunner",
    "ToolTimeout",
]
