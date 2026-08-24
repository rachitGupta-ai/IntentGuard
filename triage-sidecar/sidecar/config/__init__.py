"""Central configuration for the Alert Triage Sidecar.

Public API
----------
- ``SidecarConfig``   : validated, immutable settings model exposing every tunable.
- ``get_config()``    : returns the process-wide default configuration (cached).
- ``DEFAULTS``        : a mapping of every tunable's default value.

All tunables carry defaults and validated bounds. Constructing a
``SidecarConfig`` with an out-of-bounds value raises ``pydantic.ValidationError``.
"""

from sidecar.config.settings import DEFAULTS, SidecarConfig, get_config

__all__ = ["SidecarConfig", "get_config", "DEFAULTS"]
