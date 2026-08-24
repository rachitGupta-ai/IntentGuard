"""Service_Account credential abstraction for engine-directed requests.

Privilege separation (R1.7, R14.2) requires the sidecar to reach IntentGuard
*only* under a dedicated identity provisioned with **read-only scopes and
nothing else** — zero write, block, or enforcement scopes. This module is the
structural embodiment of that guarantee on the sidecar side:

  * :class:`ReadOnlyScope` is a *closed* enum whose members are exactly the five
    read-only capabilities the sidecar ever needs (one per Read_Tool). Because it
    is the only source of scope values a credential will accept, a write/block/
    enforcement scope simply cannot be represented.
  * :class:`ServiceAccountCredential` carries the sidecar's identity plus a
    read-only scope set and produces the ``Authorization`` header attached to
    every engine-directed request (R14.2). Construction rejects any scope that is
    not a :class:`ReadOnlyScope`, so an over-privileged credential fails fast.

The engine performs its own authorization/authentication enforcement (R14.3,
R14.4); that behaviour is exercised against a real IntentGuard harness in the
integration tests (task 21.1). Here we only model the *sidecar-side* credential
and guarantee it is least-privileged.

The tool-layer backend (``sidecar.tools.backend.ReadOnlyEngineBackend``, task
6.1) can adopt a :class:`ServiceAccountCredential` to authenticate its engine
I/O; this module deliberately does not modify that backend — it only supplies
the credential/auth abstraction it can inject.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Iterable, Mapping, Optional

# Substrings that must never appear in a granted scope name. The enum below is
# already closed to read-only members; this is a defence-in-depth check used by
# ``ServiceAccountCredential`` and available to the read-only-scopes smoke test
# (task 6.5).
FORBIDDEN_SCOPE_MARKERS: tuple[str, ...] = ("write", "block", "enforce", "mutate", "admin")


class ReadOnlyScope(str, Enum):
    """The closed set of read-only scopes the Service_Account may hold (R1.7).

    Each member authorizes exactly one read-only engine capability, aligned with
    the five Read_Tools. There is intentionally no write/block/enforcement
    member, so an over-privileged scope cannot be expressed in the type system.
    """

    READ_SESSION_HISTORY = "read:session_history"     # get_session_history (R5.1)
    READ_ACTOR_PROFILE = "read:actor_profile"         # get_actor_profile (R5.2)
    READ_AUDIT_HISTORY = "read:audit_history"          # query_audit_history (R6.3)
    READ_RELATED_ALERTS = "read:related_alerts"        # get_related_alerts (R6.1)
    READ_EXFIL_CORRELATIONS = "read:exfil_correlations"  # get_exfil_correlations (R6.2)


# The full read-only scope set granted to the sidecar's Service_Account by
# default: every read-only capability, and nothing else.
READ_ONLY_SCOPES: frozenset[ReadOnlyScope] = frozenset(ReadOnlyScope)


def _coerce_scope(value: object) -> ReadOnlyScope:
    """Coerce ``value`` to a :class:`ReadOnlyScope`, rejecting anything else.

    Any value that is not a read-only scope (including a would-be write/block/
    enforcement scope) raises ``ValueError`` — the credential can never carry it.
    """

    if isinstance(value, ReadOnlyScope):
        scope = value
    else:
        try:
            scope = ReadOnlyScope(str(value))
        except ValueError as exc:
            raise ValueError(
                f"scope {value!r} is not a read-only scope; the Service_Account "
                "may hold only read-only scopes (R1.7, R14.2)"
            ) from exc
    # Defence-in-depth: reject any scope whose name/value hints at a mutating op.
    lowered = scope.value.lower()
    if any(marker in lowered for marker in FORBIDDEN_SCOPE_MARKERS):
        raise ValueError(f"scope {scope.value!r} names a non-read-only operation")
    return scope


@dataclass(frozen=True)
class ServiceAccountCredential:
    """The sidecar's dedicated, read-only Service_Account credential (R14.2).

    Attached to every engine-directed request via :meth:`auth_headers`. The
    credential is immutable and least-privileged: ``scopes`` is validated at
    construction to contain only :class:`ReadOnlyScope` members, so it can never
    authorize a write, block, or enforcement operation (R1.7).

    ``expiresAtMs`` is optional; when set, :meth:`is_expired` reports expiry so
    callers can refresh before a request. The engine independently rejects
    missing/expired/invalid credentials (R14.4).
    """

    identity: str
    token: str
    scopes: frozenset[ReadOnlyScope] = field(default_factory=lambda: READ_ONLY_SCOPES)
    scheme: str = "Bearer"
    expiresAtMs: Optional[int] = None

    def __post_init__(self) -> None:
        if not self.identity or not self.identity.strip():
            raise ValueError("Service_Account identity must be a non-empty string")
        if not self.token or not self.token.strip():
            raise ValueError("Service_Account token must be a non-empty string")
        if not self.scheme or not self.scheme.strip():
            raise ValueError("Service_Account auth scheme must be a non-empty string")
        coerced = frozenset(_coerce_scope(s) for s in self.scopes)
        if not coerced:
            raise ValueError("Service_Account must be granted at least one read-only scope")
        # Frozen dataclass: assign the validated/coerced scope set via object.__setattr__.
        object.__setattr__(self, "scopes", coerced)

    # -- auth material -----------------------------------------------------

    @property
    def authorization_value(self) -> str:
        """The raw ``Authorization`` header value (``<scheme> <token>``)."""
        return f"{self.scheme} {self.token}"

    def auth_headers(self) -> dict[str, str]:
        """The header(s) that authenticate an engine-directed request (R14.2)."""
        return {"Authorization": self.authorization_value}

    # -- least-privilege guarantees ---------------------------------------

    @property
    def is_read_only(self) -> bool:
        """True iff every granted scope is a read-only scope (always, by construction)."""
        return all(isinstance(s, ReadOnlyScope) for s in self.scopes)

    def has_scope(self, scope: ReadOnlyScope) -> bool:
        """True iff this credential grants ``scope``."""
        return scope in self.scopes

    def is_expired(self, now_ms: int) -> bool:
        """True iff an expiry is set and ``now_ms`` is at or past it (R14.4)."""
        return self.expiresAtMs is not None and now_ms >= self.expiresAtMs


def default_service_account(
    *,
    identity: str = "triage-sidecar",
    token: str = "read-only-service-account-token",
    scopes: Optional[Iterable[ReadOnlyScope | str]] = None,
    expiresAtMs: Optional[int] = None,
) -> ServiceAccountCredential:
    """Build a least-privileged Service_Account credential.

    Defaults to the full read-only scope set. Provide ``scopes`` to narrow the
    grant; any non-read-only value raises ``ValueError``.
    """

    granted = READ_ONLY_SCOPES if scopes is None else frozenset(_coerce_scope(s) for s in scopes)
    return ServiceAccountCredential(
        identity=identity,
        token=token,
        scopes=granted,
        expiresAtMs=expiresAtMs,
    )


__all__ = [
    "ReadOnlyScope",
    "READ_ONLY_SCOPES",
    "FORBIDDEN_SCOPE_MARKERS",
    "ServiceAccountCredential",
    "default_service_account",
]
