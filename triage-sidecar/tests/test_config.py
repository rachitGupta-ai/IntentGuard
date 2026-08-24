"""Sanity tests for the central configuration module (task 1 scaffolding)."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from sidecar.config import DEFAULTS, SidecarConfig, get_config


def test_defaults_match_spec():
    cfg = SidecarConfig()
    assert cfg.probe_max_steps == 8
    assert cfg.probe_budget_seconds == 30
    assert cfg.total_investigation_budget_seconds == 300
    assert cfg.per_tool_timeout_seconds == 30
    assert cfg.correlation_result_cap == 100
    assert cfg.correlation_window_max_days == 30
    assert cfg.retention_period_hours == 24
    assert cfg.escalation_retry_max_attempts >= 1
    assert cfg.escalation_retry_interval_seconds >= 1
    assert "v1" in cfg.supported_schema_versions


def test_defaults_mapping_is_consistent():
    cfg = SidecarConfig()
    for key, value in DEFAULTS.items():
        assert getattr(cfg, key) == value


def test_derived_accessors():
    cfg = SidecarConfig()
    assert cfg.retention_period_seconds == 24 * 3600
    assert cfg.correlation_window_max_seconds == 30 * 86_400
    assert cfg.is_supported_version("v1") is True
    assert cfg.is_supported_version("v9") is False
    assert cfg.is_supported_version(None) is False


def test_config_is_frozen():
    cfg = SidecarConfig()
    with pytest.raises(ValidationError):
        cfg.probe_max_steps = 3  # type: ignore[misc]


def test_get_config_is_cached_singleton():
    assert get_config() is get_config()


@pytest.mark.parametrize(
    "field,value",
    [
        ("probe_max_steps", 0),
        ("probe_max_steps", 51),
        ("probe_budget_seconds", 0),
        ("probe_budget_seconds", 301),
        ("per_tool_timeout_seconds", 0),
        ("correlation_result_cap", 101),
        ("correlation_window_max_days", 31),
        ("retention_period_hours", 0),
        ("retention_period_hours", 169),
    ],
)
def test_out_of_bounds_values_rejected(field, value):
    with pytest.raises(ValidationError):
        SidecarConfig(**{field: value})


def test_empty_schema_version_set_rejected():
    with pytest.raises(ValidationError):
        SidecarConfig(supported_schema_versions=[])
