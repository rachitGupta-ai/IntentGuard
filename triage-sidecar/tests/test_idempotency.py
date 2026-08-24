"""Unit tests for the alertId-keyed idempotency store (task 5.1, R3).

These are example/edge-case tests. The universal properties (5.2 idempotent
admission, 5.3 report identity, 5.4 retention boundary) are covered separately
by the property-based tests.
"""

from __future__ import annotations

import threading

import pytest

from sidecar.config import SidecarConfig
from sidecar.models import ThreatCategory, TriggerType, VerdictValue
from sidecar.models import Triage_Report
from sidecar.triage import (
    AdmissionOutcome,
    IdempotencyStore,
    RunState,
)


def _report(alert_id: str, confidence: float = 0.5) -> Triage_Report:
    """Build a minimal valid Triage_Report for a given alertId."""
    return Triage_Report(
        alertId=alert_id,
        triggerType=TriggerType.CANARY_TOKEN,
        verdict=VerdictValue.BENIGN,
        confidence=confidence,
        threatCategory=ThreatCategory.FALSE_POSITIVE,
        evidence=[],
        noEvidenceFlag=True,
        schemaVersion="v1",
    )


class _MutableClock:
    """A deterministic, advanceable clock returning epoch seconds."""

    def __init__(self, start: float = 1000.0) -> None:
        self.t = start

    def __call__(self) -> float:
        return self.t

    def advance(self, seconds: float) -> None:
        self.t += seconds


def test_first_trigger_is_admitted_new() -> None:
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    result = store.admit("alert-1")
    assert result.outcome is AdmissionOutcome.ADMITTED_NEW
    assert result.admitted is True
    assert result.report is None
    assert store.state_of("alert-1") is RunState.IN_PROGRESS


def test_duplicate_while_in_progress_returns_in_progress() -> None:
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    store.admit("alert-1")
    result = store.admit("alert-1")
    assert result.outcome is AdmissionOutcome.IN_PROGRESS
    assert result.admitted is False
    assert result.report is None


def test_completed_within_retention_returns_stored_report() -> None:
    clock = _MutableClock()
    store = IdempotencyStore(config=SidecarConfig(), clock=clock)
    store.admit("alert-1")
    report = _report("alert-1")
    store.mark_completed("alert-1", report)

    result = store.admit("alert-1")
    assert result.outcome is AdmissionOutcome.COMPLETED
    assert result.admitted is False
    assert result.report is report


def test_trigger_after_retention_starts_new_run() -> None:
    clock = _MutableClock()
    config = SidecarConfig(retention_period_hours=1)  # 3600 s
    store = IdempotencyStore(config=config, clock=clock)
    store.admit("alert-1")
    store.mark_completed("alert-1", _report("alert-1"))

    # Just past the retention window -> new run.
    clock.advance(config.retention_period_seconds + 1)
    result = store.admit("alert-1")
    assert result.outcome is AdmissionOutcome.ADMITTED_NEW
    assert store.state_of("alert-1") is RunState.IN_PROGRESS


def test_retention_boundary_is_inclusive() -> None:
    clock = _MutableClock()
    config = SidecarConfig(retention_period_hours=1)
    store = IdempotencyStore(config=config, clock=clock)
    store.admit("alert-1")
    report = _report("alert-1")
    store.mark_completed("alert-1", report)

    # Exactly at the boundary -> still within the window (R3.7).
    clock.advance(config.retention_period_seconds)
    result = store.admit("alert-1")
    assert result.outcome is AdmissionOutcome.COMPLETED
    assert result.report is report


def test_injected_now_overrides_clock() -> None:
    config = SidecarConfig(retention_period_hours=1)
    store = IdempotencyStore(config=config, clock=_MutableClock(start=0.0))
    store.admit("alert-1", now=0.0)
    store.mark_completed("alert-1", _report("alert-1"), now=0.0)

    # Inject a now beyond retention regardless of the clock.
    result = store.admit("alert-1", now=config.retention_period_seconds + 1)
    assert result.outcome is AdmissionOutcome.ADMITTED_NEW


def test_independent_alert_ids_do_not_interfere() -> None:
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    a = store.admit("alert-1")
    b = store.admit("alert-2")
    assert a.outcome is AdmissionOutcome.ADMITTED_NEW
    assert b.outcome is AdmissionOutcome.ADMITTED_NEW


def test_lookup_and_state_of_unknown_alert() -> None:
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    assert store.lookup("nope") is None
    assert store.state_of("nope") is None


def test_empty_alert_id_rejected() -> None:
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    with pytest.raises(ValueError):
        store.admit("")
    with pytest.raises(ValueError):
        store.mark_completed("", _report("x"))


def test_concurrent_first_triggers_admit_exactly_one() -> None:
    """Simultaneous first-time triggers resolve to exactly one admitted run (R3.4, R3.5)."""
    store = IdempotencyStore(config=SidecarConfig(), clock=_MutableClock())
    outcomes: list[AdmissionOutcome] = []
    outcomes_lock = threading.Lock()
    start = threading.Barrier(20)

    def worker() -> None:
        start.wait()
        res = store.admit("alert-hot")
        with outcomes_lock:
            outcomes.append(res.outcome)

    threads = [threading.Thread(target=worker) for _ in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    admitted = [o for o in outcomes if o is AdmissionOutcome.ADMITTED_NEW]
    in_progress = [o for o in outcomes if o is AdmissionOutcome.IN_PROGRESS]
    assert len(admitted) == 1
    assert len(in_progress) == 19
