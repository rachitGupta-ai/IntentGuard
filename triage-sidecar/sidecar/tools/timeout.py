"""Per-tool wall-clock timeout mechanism for the read-only tool layer.

Every Read_Tool call is bounded by a configured per-tool wall-clock timeout
(30s, R5.1/R5.2/R7.2). The enforcement is factored behind a small
``TimeoutRunner`` protocol so that:

  * production code uses :class:`ThreadTimeoutRunner`, which runs the backend
    call on a worker thread and abandons it if it overruns the deadline, and
  * tests inject a deterministic double (e.g. one that runs inline or always
    times out) instead of sleeping against a real clock.

A call that overruns its deadline raises :class:`ToolTimeout`; the tool layer
turns that (like any other failure) into a recorded gap rather than a hard
failure (R5.3, R6.5, R8.8).
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeoutError
from typing import Callable, Protocol, TypeVar, runtime_checkable

_T = TypeVar("_T")


class ToolTimeout(Exception):
    """Raised when a Read_Tool call exceeds its per-tool wall-clock timeout."""


@runtime_checkable
class TimeoutRunner(Protocol):
    """Runs a zero-argument callable under a wall-clock timeout.

    Implementations MUST either return the callable's result or raise
    :class:`ToolTimeout` if it does not complete within ``timeout_seconds``.
    Any exception raised by ``func`` itself propagates unchanged.
    """

    def run(self, func: Callable[[], _T], timeout_seconds: float) -> _T:  # pragma: no cover - protocol
        ...


class ThreadTimeoutRunner:
    """Default runner: execute ``func`` on a worker thread with a hard deadline.

    If the call overruns ``timeout_seconds`` the worker is abandoned (Python
    cannot forcibly kill a thread) and :class:`ToolTimeout` is raised. Because
    every tool call is strictly read-only and side-effect free, abandoning an
    overrunning read is safe.
    """

    def run(self, func: Callable[[], _T], timeout_seconds: float) -> _T:
        executor = ThreadPoolExecutor(max_workers=1)
        future = executor.submit(func)
        try:
            return future.result(timeout=timeout_seconds)
        except FuturesTimeoutError as exc:
            future.cancel()
            raise ToolTimeout(
                f"read tool exceeded per-tool wall-clock timeout of {timeout_seconds}s"
            ) from exc
        finally:
            # Do not block shutdown on an abandoned, overrunning worker.
            executor.shutdown(wait=False)


class InlineTimeoutRunner:
    """Deterministic runner that invokes ``func`` inline, ignoring the clock.

    Intended for tests that supply fast, deterministic backend doubles and do
    not want real wall-clock behaviour. It never raises :class:`ToolTimeout`
    on its own; a test that wants to exercise the timeout path should either
    inject a backend double that raises :class:`ToolTimeout` or use a runner
    such as one that always times out.
    """

    def run(self, func: Callable[[], _T], timeout_seconds: float) -> _T:
        return func()


__all__ = ["ToolTimeout", "TimeoutRunner", "ThreadTimeoutRunner", "InlineTimeoutRunner"]
