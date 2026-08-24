"""Escalation-delivery retry for the human-in-the-loop pause (R11.6, R11.9).

Persist-then-pause (task 19.1) durably checkpoints an investigation and marks it
paused. Separately, the escalation itself has to be *delivered* to the
Control_Tower so a human is actually notified. Delivery can fail transiently
(the Control_Tower is briefly unreachable). This module owns that concern:

* **Retain-and-retry (R11.6).** On an undeliverable escalation the paused,
  checkpointed state is retained unchanged and delivery is retried up to
  ``escalation_retry_max_attempts`` at ``escalation_retry_interval_seconds``
  (:class:`SidecarConfig`). The investigation is never completed or advanced
  while retrying.
* **Exhaustion (R11.9).** If every attempt fails, an
  :class:`UndeliveredEscalationError` (the *undelivered-escalation alert*) is
  raised and the investigation stays paused and checkpointed.
* **Success.** If any attempt within the budget succeeds, delivery reports
  success. Delivery success is *not* a human decision: the investigation stays
  paused awaiting the Control_Tower decision handled by the HITL manager.

Delivery transport is behind the small injectable :class:`EscalationDelivery`
interface (mirroring the checkpoint-store / report-transport pattern), so this
logic is unit-testable without a live Control_Tower. The wait between attempts
is an injectable ``sleep`` callable so tests incur no real delay.

Deliberately separate from dual-control (task 19.3): this module only decides
*whether the escalation reached the Control_Tower*, never *whether an action may
proceed*. The two coordinate through the HITL manager but stay decoupled.

Public API
----------
- ``UndeliveredEscalationError`` : the undelivered-escalation alert raised on exhaustion (R11.9).
- ``EscalationDeliveryResult``   : the outcome of a delivery attempt sequence.
- ``EscalationDelivery``         : the injectable delivery interface (Protocol).
- ``ScriptedEscalationDelivery`` : a testable double (fail N times then succeed / always fail).
- ``EscalationRetryDriver``      : the retain-and-retry driver (R11.6, R11.9).
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional, Protocol, runtime_checkable

from sidecar.config import SidecarConfig, get_config


class UndeliveredEscalationError(RuntimeError):
    """The undelivered-escalation alert raised when retries are exhausted (R11.9).

    When this is raised the investigation stays paused and checkpointed and is
    never completed or advanced. ``attempts`` is the number of delivery attempts
    made (equal to the configured maximum) and ``detail`` carries the last
    failure reason, when one was reported.
    """

    def __init__(self, alert_id: str, attempts: int, detail: Optional[str] = None) -> None:
        self.alert_id = alert_id
        self.attempts = attempts
        self.detail = detail
        message = (
            f"escalation for alertId {alert_id!r} could not be delivered to the "
            f"Control_Tower after {attempts} attempt(s)"
        )
        if detail:
            message = f"{message}: {detail}"
        super().__init__(message)


@dataclass(frozen=True)
class EscalationDeliveryResult:
    """The outcome of an escalation-delivery attempt sequence.

    ``delivered`` is ``True`` only when an attempt succeeded within the configured
    maximum; ``attempts`` is the number of attempts actually made. Delivery
    success does **not** advance the investigation — it remains paused awaiting a
    Control_Tower decision.
    """

    alertId: str
    delivered: bool
    attempts: int
    detail: Optional[str] = None


@runtime_checkable
class EscalationDelivery(Protocol):
    """Injectable transport that delivers an escalation to the Control_Tower (R11.6).

    An implementation attempts to notify the Control_Tower that ``alert_id`` has
    been escalated to a human. It returns ``True`` on successful delivery and
    either returns ``False`` or raises to signal an undeliverable escalation; the
    retry driver treats both the same way. A real HTTP client is wired in at
    task 20.x; :class:`ScriptedEscalationDelivery` is the testable default.
    """

    def deliver(self, alert_id: str, payload: Any) -> bool:
        """Attempt to deliver the escalation for ``alert_id``; True iff delivered."""
        ...


class ScriptedEscalationDelivery:
    """A testable :class:`EscalationDelivery` with a scripted failure pattern.

    Configure it to fail a fixed number of times before succeeding, or to always
    fail, and it records every attempt so tests can assert the attempt count.

    :param fail_times: number of leading attempts that fail before delivery
        succeeds. ``0`` means the first attempt succeeds.
    :param always_fail: when ``True`` every attempt fails (``fail_times`` ignored),
        exercising the exhaustion / undelivered-escalation path (R11.9).
    :param raise_on_failure: when ``True`` a failing attempt raises
        :class:`ConnectionError`; when ``False`` it returns ``False``. Both are
        treated as undeliverable by the driver.
    """

    def __init__(
        self,
        *,
        fail_times: int = 0,
        always_fail: bool = False,
        raise_on_failure: bool = False,
    ) -> None:
        self._fail_times = fail_times
        self._always_fail = always_fail
        self._raise_on_failure = raise_on_failure
        self._lock = threading.Lock()
        self._attempts: list[tuple[str, Any]] = []

    def deliver(self, alert_id: str, payload: Any) -> bool:
        with self._lock:
            self._attempts.append((alert_id, payload))
            attempt_number = len(self._attempts)
            fails = self._always_fail or attempt_number <= self._fail_times
        if fails:
            if self._raise_on_failure:
                raise ConnectionError(
                    f"Control_Tower unreachable (attempt {attempt_number})"
                )
            return False
        return True

    @property
    def attempts(self) -> int:
        """Number of delivery attempts made so far."""
        with self._lock:
            return len(self._attempts)

    @property
    def calls(self) -> list[tuple[str, Any]]:
        """The (alertId, payload) pairs delivery was attempted with, in order."""
        with self._lock:
            return list(self._attempts)


class EscalationRetryDriver:
    """Retain-and-retry driver for escalation delivery (R11.6, R11.9).

    Attempts delivery through the injected :class:`EscalationDelivery` up to
    ``escalation_retry_max_attempts`` (:class:`SidecarConfig`), waiting
    ``escalation_retry_interval_seconds`` between attempts via the injectable
    ``sleep`` callable. On success it returns an
    :class:`EscalationDeliveryResult`; on exhausting every attempt it raises an
    :class:`UndeliveredEscalationError` (R11.9).

    The driver never completes or advances an investigation — it only reports the
    delivery outcome. The HITL manager keeps the investigation paused and
    checkpointed throughout.

    :param delivery: the delivery transport to attempt through.
    :param config: configuration supplying the retry bounds; defaults to the
        process configuration.
    :param sleep: the wait-between-attempts callable, injectable so tests incur
        no real delay; defaults to :func:`time.sleep`.
    """

    def __init__(
        self,
        delivery: EscalationDelivery,
        *,
        config: Optional[SidecarConfig] = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._delivery = delivery
        self._config = config or get_config()
        self._sleep = sleep

    @property
    def max_attempts(self) -> int:
        """The configured maximum number of delivery attempts (R11.6)."""
        return self._config.escalation_retry_max_attempts

    @property
    def interval_seconds(self) -> int:
        """The configured wait between delivery attempts, in seconds (R11.6)."""
        return self._config.escalation_retry_interval_seconds

    def deliver_with_retry(self, alert_id: str, payload: Any) -> EscalationDeliveryResult:
        """Deliver ``payload`` for ``alert_id``, retrying to the max (R11.6, R11.9).

        Retries up to :attr:`max_attempts`, sleeping :attr:`interval_seconds`
        between attempts. Returns an :class:`EscalationDeliveryResult` with
        ``delivered=True`` on the first success. If every attempt fails, raises
        :class:`UndeliveredEscalationError`; the caller keeps the investigation
        paused and checkpointed (R11.9).

        :raises UndeliveredEscalationError: if delivery fails on every attempt.
        """
        max_attempts = self.max_attempts
        last_detail: Optional[str] = None

        for attempt in range(1, max_attempts + 1):
            delivered = False
            try:
                delivered = bool(self._delivery.deliver(alert_id, payload))
            except Exception as exc:  # noqa: BLE001 - any transport error is undeliverable
                last_detail = str(exc)
            else:
                if not delivered:
                    last_detail = "delivery reported failure"

            if delivered:
                return EscalationDeliveryResult(
                    alertId=alert_id,
                    delivered=True,
                    attempts=attempt,
                )

            # Undeliverable: wait the configured interval before the next attempt.
            # No sleep after the final attempt — retries are exhausted.
            if attempt < max_attempts:
                self._sleep(float(self.interval_seconds))

        # Every attempt failed: raise the undelivered-escalation alert (R11.9).
        raise UndeliveredEscalationError(alert_id, max_attempts, last_detail)


__all__ = [
    "UndeliveredEscalationError",
    "EscalationDeliveryResult",
    "EscalationDelivery",
    "ScriptedEscalationDelivery",
    "EscalationRetryDriver",
]
