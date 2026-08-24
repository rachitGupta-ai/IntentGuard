"""Unit tests for escalation-delivery retry (task 19.5, R11.6, R11.9).

Example / edge-case coverage for the retain-and-retry driver and its HITL
manager integration:
  * delivery succeeding on the Nth attempt within the configured maximum returns
    a success result with the right attempt count and no real sleeping (R11.6)
  * an always-failing delivery exhausts exactly ``max_attempts`` and raises the
    undelivered-escalation alert while the investigation stays paused and
    checkpointed (R11.9)
  * the wait between attempts is the configured interval and is injectable, so no
    real delay is incurred, and no wait follows the final attempt
  * both a ``False`` return and a raised transport error count as undeliverable

Property 20 (universal) is covered separately by the property-based test (19.6).
"""

from __future__ import annotations

import pytest

from sidecar.config import SidecarConfig
from sidecar.hitl import (
    EscalationRetryDriver,
    HITLManager,
    InMemoryCheckpointStore,
    ScriptedEscalationDelivery,
    UndeliveredEscalationError,
)
from sidecar.hitl.delivery import EscalationDelivery
from sidecar.models import AlertEnvelope, TriageState, TriggerType


def _state(alert_id: str = "alert-1") -> TriageState:
    envelope = AlertEnvelope(
        schemaVersion="v1",
        alertId=alert_id,
        triggerType=TriggerType.CANARY_TOKEN,
        alertTimestampMs=1_000,
        signalPayload={"canary": "hit"},
    )
    return TriageState(
        alertId=alert_id,
        envelope=envelope,
        triggerType=TriggerType.CANARY_TOKEN,
        investigation_started_ms=1_000,
        escalated=True,
    )


class _RecordingSleep:
    """A sleep double that records the waits it was asked to make (no real delay)."""

    def __init__(self) -> None:
        self.waits: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.waits.append(seconds)


def _config(max_attempts: int = 5, interval: int = 30) -> SidecarConfig:
    return SidecarConfig(
        escalation_retry_max_attempts=max_attempts,
        escalation_retry_interval_seconds=interval,
    )


# --- retry driver: success within the budget (R11.6) ------------------------


def test_delivers_on_first_attempt_without_sleeping() -> None:
    delivery = ScriptedEscalationDelivery(fail_times=0)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(), sleep=sleep)

    result = driver.deliver_with_retry("alert-1", {"report": "x"})

    assert result.delivered is True
    assert result.attempts == 1
    assert delivery.attempts == 1
    assert sleep.waits == []  # succeeded immediately; no wait


def test_delivers_on_nth_attempt_within_max() -> None:
    # Fails 3 times, succeeds on the 4th; max is 5 so it succeeds within budget.
    delivery = ScriptedEscalationDelivery(fail_times=3)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(max_attempts=5, interval=30), sleep=sleep)

    result = driver.deliver_with_retry("alert-1", {"report": "x"})

    assert result.delivered is True
    assert result.attempts == 4
    assert delivery.attempts == 4
    # One wait before each of the 3 retries (attempts 2, 3, 4); none after success.
    assert sleep.waits == [30.0, 30.0, 30.0]


def test_wait_uses_configured_interval() -> None:
    delivery = ScriptedEscalationDelivery(fail_times=1)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(interval=7), sleep=sleep)

    driver.deliver_with_retry("alert-1", None)

    assert sleep.waits == [7.0]


# --- retry driver: exhaustion raises the undelivered-escalation alert (R11.9) ---


def test_exhausts_max_attempts_and_raises() -> None:
    delivery = ScriptedEscalationDelivery(always_fail=True)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(max_attempts=5, interval=30), sleep=sleep)

    with pytest.raises(UndeliveredEscalationError) as excinfo:
        driver.deliver_with_retry("alert-1", {"report": "x"})

    assert excinfo.value.alert_id == "alert-1"
    assert excinfo.value.attempts == 5
    # Exactly max_attempts deliveries were attempted.
    assert delivery.attempts == 5
    # A wait precedes every retry but not the final (exhausted) attempt: 4 waits.
    assert sleep.waits == [30.0, 30.0, 30.0, 30.0]


def test_raised_transport_error_counts_as_undeliverable() -> None:
    delivery = ScriptedEscalationDelivery(always_fail=True, raise_on_failure=True)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(max_attempts=3), sleep=sleep)

    with pytest.raises(UndeliveredEscalationError) as excinfo:
        driver.deliver_with_retry("alert-1", None)

    assert excinfo.value.attempts == 3
    assert delivery.attempts == 3
    assert excinfo.value.detail is not None  # last failure reason captured


def test_single_attempt_config_makes_no_wait_and_raises() -> None:
    delivery = ScriptedEscalationDelivery(always_fail=True)
    sleep = _RecordingSleep()
    driver = EscalationRetryDriver(delivery, config=_config(max_attempts=1), sleep=sleep)

    with pytest.raises(UndeliveredEscalationError):
        driver.deliver_with_retry("alert-1", None)

    assert delivery.attempts == 1
    assert sleep.waits == []  # only one attempt, so nothing to wait between


# --- HITL manager integration: stays paused/checkpointed throughout ---------


def test_manager_delivery_success_keeps_investigation_paused() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    manager.escalate_and_pause(_state())
    delivery = ScriptedEscalationDelivery(fail_times=2)
    sleep = _RecordingSleep()

    result = manager.deliver_escalation(
        "alert-1", {"report": "x"}, delivery, config=_config(), sleep=sleep
    )

    assert result.delivered is True
    assert result.attempts == 3
    # Delivery success is NOT a decision: the investigation stays paused (R11).
    assert manager.is_paused("alert-1") is True
    assert "alert-1" in store.retained_alert_ids


def test_manager_delivery_exhaustion_stays_paused_and_checkpointed() -> None:
    store = InMemoryCheckpointStore()
    manager = HITLManager(store)
    manager.escalate_and_pause(_state())
    delivery = ScriptedEscalationDelivery(always_fail=True)
    sleep = _RecordingSleep()

    with pytest.raises(UndeliveredEscalationError):
        manager.deliver_escalation(
            "alert-1", {"report": "x"}, delivery, config=_config(max_attempts=5), sleep=sleep
        )

    # R11.9: still paused and checkpointed; never advanced.
    assert manager.is_paused("alert-1") is True
    assert "alert-1" in store.retained_alert_ids
    # No Control_Tower decision was recorded by an undelivered escalation.
    assert manager.recorded_decisions("alert-1") == []


def test_manager_rejects_delivery_when_not_paused() -> None:
    manager = HITLManager()
    delivery = ScriptedEscalationDelivery(fail_times=0)

    with pytest.raises(ValueError):
        manager.deliver_escalation("unknown-alert", None, delivery, sleep=lambda _s: None)


# --- interface conformance --------------------------------------------------


def test_scripted_delivery_is_escalation_delivery_instance() -> None:
    assert isinstance(ScriptedEscalationDelivery(), EscalationDelivery)
