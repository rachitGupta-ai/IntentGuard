# Alert Triage Sidecar

An advisory, read-mostly Python 3.11+ / LangGraph service that automates the
investigative first pass on high-risk alerts raised by the authoritative
**IntentGuard** semantic-firewall engine (the Java/Spring Boot service in the
repository root). The sidecar is **strictly advisory**: it never blocks, allows,
enforces, or mutates protected state. Its guiding safety property is
**fail-open-to-human, never fail-open-to-allow**.

See `.kiro/specs/alert-triage-sidecar/` for the full requirements, design, and
task plan.

## Package layout

```
triage-sidecar/
├── pyproject.toml           # build metadata, pinned deps, pytest config
├── requirements.txt         # pinned runtime deps
├── requirements-dev.txt     # pinned runtime + test deps
├── README.md
├── sidecar/                 # the application package
│   ├── __init__.py
│   ├── config/              # central configuration (tunables + validated bounds)
│   │   ├── __init__.py
│   │   └── settings.py
│   ├── models/              # Pydantic data models (task 2)
│   ├── contract/            # versioned Integration_Contract adapter (task 3)
│   ├── triage/              # Investigation_Graph nodes + orchestration (tasks 9-20)
│   ├── tools/               # read-only Read_Tool layer (task 6)
│   └── hitl/                # checkpoint / human-in-the-loop manager (task 19)
└── tests/                   # test tree (mirrors the sidecar package)
    ├── __init__.py
    ├── conftest.py          # Hypothesis profile: >= 100 iterations/property test
    └── test_config.py
```

## Setup

```bash
cd triage-sidecar
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt   # runtime + test deps
# or, editable install with test extras:
# pip install -e ".[test]"
```

## Running tests

```bash
cd triage-sidecar
pytest                       # all tests
pytest -m property           # property-based (Hypothesis) tests only
pytest -m "not property"     # example/unit tests only
```

Property tests run a minimum of **100 iterations** each (the `sidecar` Hypothesis
profile, activated in `tests/conftest.py`). Use a denser run with:

```bash
HYPOTHESIS_PROFILE=sidecar-thorough pytest -m property
```

## Configuration

All tunables live in `sidecar.config` and are validated at construction time
(out-of-bounds values raise `pydantic.ValidationError`).

### Public API

- `SidecarConfig` — immutable, validated settings model.
- `get_config()` — cached process-wide default configuration.
- `DEFAULTS` — mapping of every tunable's default value.

`SidecarConfig` is frozen; derive a variant with
`config.model_copy(update={...})`.

### Tunables

| Field | Bounds | Default | Requirement |
|---|---|---|---|
| `probe_max_steps` | 1–50 | 8 | R8.2 |
| `probe_budget_seconds` | 1–300 | 30 | R8.3 |
| `total_investigation_budget_seconds` | 1–3600 | 300 | R13.3 |
| `per_tool_timeout_seconds` | 1–300 | 30 | R5.1, R5.2, R7.2 |
| `correlation_result_cap` | 1–100 | 100 | R6.1, R6.2 |
| `correlation_window_max_days` | 1–30 | 30 | R6.3 |
| `retention_period_hours` | 1–168 | 24 | R3.7 |
| `escalation_retry_max_attempts` | 1–100 | 5 | R11.6 |
| `escalation_retry_interval_seconds` | 1–3600 | 30 | R11.6 |
| `supported_schema_versions` | non-empty set | `{"v1"}` | R14.5 |

### Derived accessors

- `retention_period_seconds` — retention window in seconds.
- `correlation_window_max_seconds` — correlation window cap in seconds.
- `is_supported_version(version)` — `True` iff `version` is present and supported (R14.5).
