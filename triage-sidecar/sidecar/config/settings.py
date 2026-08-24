"""Validated configuration model for the Alert Triage Sidecar.

Every tunable required by the spec is exposed here with a default and, where the
spec defines one, a validated bound. Bounds are enforced by pydantic field
constraints so an out-of-range value fails fast at construction time.

Requirements covered by these tunables:
  * probe max steps (1-50, default 8) ................. R8.2
  * probe wall-clock budget (1-300s, default 30s) ..... R8.3
  * total investigation budget (300s) ................. R13.3
  * per-tool wall-clock timeout (30s) ................. R5.1, R5.2, R7.2
  * correlation result cap (100) ...................... R6.1, R6.2
  * correlation window cap (30 days) .................. R6.3
  * retention period (1-168h, default 24h) ............ R3.7
  * escalation retry max attempts + interval .......... R11.6
  * supported schema-version set ...................... R14.5
"""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator

# --- Fixed unit conversions (not tunable) ---------------------------------
_SECONDS_PER_HOUR = 3600
_SECONDS_PER_DAY = 86_400


class SidecarConfig(BaseModel):
    """Immutable, validated configuration for the sidecar.

    Instances are frozen; derive a modified copy with ``config.model_copy(update=...)``.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    # --- Probe loop bounds (R8.2, R8.3) -----------------------------------
    probe_max_steps: Annotated[int, Field(ge=1, le=50)] = 8
    probe_budget_seconds: Annotated[int, Field(ge=1, le=300)] = 30

    # --- Investigation-wide budgets (R13.3) -------------------------------
    total_investigation_budget_seconds: Annotated[int, Field(ge=1, le=3600)] = 300

    # --- Per-tool timeout (R5.1, R5.2, R7.2) ------------------------------
    per_tool_timeout_seconds: Annotated[int, Field(ge=1, le=300)] = 30

    # --- Correlation bounds (R6.1, R6.2, R6.3) ----------------------------
    correlation_result_cap: Annotated[int, Field(ge=1, le=100)] = 100
    correlation_window_max_days: Annotated[int, Field(ge=1, le=30)] = 30

    # --- Idempotency retention window (R3.7) ------------------------------
    # Stored in hours; bounds 1..168 (1 hour .. 7 days), default 24 hours.
    retention_period_hours: Annotated[int, Field(ge=1, le=168)] = 24

    # --- Escalation-delivery retry (R11.6) --------------------------------
    escalation_retry_max_attempts: Annotated[int, Field(ge=1, le=100)] = 5
    escalation_retry_interval_seconds: Annotated[int, Field(ge=1, le=3600)] = 30

    # --- Supported Integration_Contract schema versions (R14.5) -----------
    supported_schema_versions: frozenset[str] = frozenset({"v1"})

    @field_validator("supported_schema_versions", mode="before")
    @classmethod
    def _coerce_versions(cls, value: object) -> frozenset[str]:
        """Accept any iterable of version strings; require a non-empty set."""
        if isinstance(value, str):
            value = {value}
        try:
            versions = frozenset(str(v) for v in value)  # type: ignore[arg-type]
        except TypeError as exc:  # pragma: no cover - defensive
            raise ValueError(
                "supported_schema_versions must be an iterable of version strings"
            ) from exc
        if not versions:
            raise ValueError("supported_schema_versions must contain at least one version")
        if any(not v.strip() for v in versions):
            raise ValueError("supported_schema_versions must not contain empty version strings")
        return versions

    # --- Derived convenience accessors ------------------------------------
    @property
    def retention_period_seconds(self) -> int:
        """Retention window expressed in seconds."""
        return self.retention_period_hours * _SECONDS_PER_HOUR

    @property
    def correlation_window_max_seconds(self) -> int:
        """Correlation window cap expressed in seconds."""
        return self.correlation_window_max_days * _SECONDS_PER_DAY

    def is_supported_version(self, version: str | None) -> bool:
        """True iff ``version`` is present and in the supported set (R14.5)."""
        return version is not None and version in self.supported_schema_versions


# A plain mapping of default values, handy for docs, tests, and diagnostics.
DEFAULTS: dict[str, object] = {
    "probe_max_steps": 8,
    "probe_budget_seconds": 30,
    "total_investigation_budget_seconds": 300,
    "per_tool_timeout_seconds": 30,
    "correlation_result_cap": 100,
    "correlation_window_max_days": 30,
    "retention_period_hours": 24,
    "escalation_retry_max_attempts": 5,
    "escalation_retry_interval_seconds": 30,
    "supported_schema_versions": frozenset({"v1"}),
}


@lru_cache(maxsize=1)
def get_config() -> SidecarConfig:
    """Return the process-wide default configuration (cached singleton)."""
    return SidecarConfig()
