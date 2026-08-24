"""Shared pytest / Hypothesis configuration for the Alert Triage Sidecar tests.

Registers a Hypothesis profile that runs a minimum of 100 iterations per
property-based test, as required by the implementation plan, and activates it
by default. Override at runtime with ``--hypothesis-profile=<name>`` or the
``HYPOTHESIS_PROFILE`` environment variable.
"""

from __future__ import annotations

import os

# Minimum iterations per property test (design/plan requirement).
MIN_PROPERTY_ITERATIONS = 100

try:
    from hypothesis import HealthCheck, settings

    settings.register_profile(
        "sidecar",
        max_examples=MIN_PROPERTY_ITERATIONS,
        deadline=None,
        suppress_health_check=[HealthCheck.too_slow],
    )
    # A denser profile for local exploration / CI hardening.
    settings.register_profile(
        "sidecar-thorough",
        max_examples=MIN_PROPERTY_ITERATIONS * 5,
        deadline=None,
        suppress_health_check=[HealthCheck.too_slow],
    )
    settings.load_profile(os.environ.get("HYPOTHESIS_PROFILE", "sidecar"))
except ImportError:
    # Hypothesis is a test-only dependency; allow collection without it so
    # that non-property tests can still run in a minimal environment.
    pass
