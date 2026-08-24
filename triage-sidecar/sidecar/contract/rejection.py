"""Rejected-message recording for the Integration_Contract inbound path.

Two inbound conditions require the sidecar to *record the rejected message*
before/without processing its contents:

  * An absent or unsupported ``schemaVersion`` (R14.5) - recorded with its
    declared version *before any content is processed*.
  * A malformed alert missing a required field / non-empty ``alertId`` (R2.6,
    R4.2) - recorded together with the field that was missing or invalid.

This module provides the small ``RejectedMessage`` record, the
``RejectionRecorder`` protocol (the injection point downstream/production code
implements against a durable store), and an ``InMemoryRejectionRecorder``
default suitable for wiring and tests.
"""

from __future__ import annotations

from dataclasses import dataclass, field as dataclass_field
from enum import Enum
from typing import Any, Mapping, Optional, Protocol, runtime_checkable


class RejectionReason(str, Enum):
    """Why an inbound message was rejected at the contract boundary."""

    UNSUPPORTED_VERSION = "unsupported_version"  # absent or out-of-set version (R14.5)
    MISSING_FIELD = "missing_field"              # missing/invalid required field (R2.6, R4.2)
    MALFORMED = "malformed"                      # unparseable / not an object


@dataclass(frozen=True)
class RejectedMessage:
    """An immutable record of a message rejected before investigation.

    ``rawMessage`` retains the original payload so the rejection is auditable.
    ``declaredVersion`` is the ``schemaVersion`` the message claimed (or ``None``
    when absent). ``field`` names the missing/invalid field for malformed
    rejections. ``detail`` is a human-readable explanation.
    """

    reason: RejectionReason
    detail: str
    declaredVersion: Optional[str] = None
    field: Optional[str] = None
    rawMessage: Mapping[str, Any] = dataclass_field(default_factory=dict)


@runtime_checkable
class RejectionRecorder(Protocol):
    """Injection point for durably recording rejected inbound messages.

    Production wiring backs this with the engine's audit sink; the in-memory
    implementation below is the default used for local wiring and tests.
    """

    def record(self, rejected: RejectedMessage) -> None:
        """Persist a single rejected message."""
        ...


class InMemoryRejectionRecorder:
    """A simple in-memory ``RejectionRecorder`` that appends to a list.

    Useful as a default and for tests that assert a rejection was recorded.
    """

    def __init__(self) -> None:
        self._records: list[RejectedMessage] = []

    def record(self, rejected: RejectedMessage) -> None:
        self._records.append(rejected)

    @property
    def records(self) -> list[RejectedMessage]:
        """The rejected messages recorded so far, in arrival order."""
        return list(self._records)


__all__ = [
    "RejectionReason",
    "RejectedMessage",
    "RejectionRecorder",
    "InMemoryRejectionRecorder",
]
