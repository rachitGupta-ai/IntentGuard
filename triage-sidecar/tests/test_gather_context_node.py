"""Unit tests for the ``gather_context`` node (task 10.1, R5.1-R5.5).

Exercises the node against a deterministic :class:`StaticEngineBackend` +
:class:`InlineTimeoutRunner`, covering:

  * both context sources gathered, record-id-tagged, and Untrusted-classified,
  * routing to ``correlate`` on completion,
  * no-data / error / timeout on one source recorded as a gap while the other
    source's context is preserved (R5.3),
  * unbindable records excluded and recorded as a partial gap (R5.5),
  * a missing ``actorId`` (engine-wide triggers) recorded as a gap without
    crashing (R5.2/R5.3).
"""

from __future__ import annotations

from sidecar.models import AlertEnvelope, TriageState, TriggerType
from sidecar.tools import (
    EngineRecord,
    InlineTimeoutRunner,
    ReadToolRegistry,
    StaticEngineBackend,
    ToolTimeout,
    build_registry,
)
from sidecar.triage.nodes.gather_context import (
    MISSING_ACTOR_ID_REASON,
    ROUTE_CORRELATE,
    gather_context,
)

_ALERT_TS_MS = 1_700_000_000_000


def _records(n: int, prefix: str) -> list[EngineRecord]:
    return [
        EngineRecord(auditRecordId=f"{prefix}-{i}", summary=f"{prefix} row {i}")
        for i in range(n)
    ]


def _envelope(*, actor_id: str | None = "actor-1") -> AlertEnvelope:
    return AlertEnvelope(
        schemaVersion="v1",
        alertId="alert-1",
        triggerType=TriggerType.BLOCK_RANGE_DIVERGENCE,
        actorId=actor_id,
        sessionId="sess-1",
        alertTimestampMs=_ALERT_TS_MS,
        signalPayload={},
    )


def _state(envelope: AlertEnvelope | None = None) -> TriageState:
    env = envelope if envelope is not None else _envelope()
    return TriageState(
        alertId=env.alertId,
        envelope=env,
        triggerType=env.triggerType,
        investigation_started_ms=_ALERT_TS_MS,
    )


def _registry(**backend_kwargs) -> ReadToolRegistry:
    backend = StaticEngineBackend(**backend_kwargs)
    return build_registry(backend, runner=InlineTimeoutRunner())


# --- happy path: both sources gathered and bound --------------------------


def test_gathers_session_and_actor_context_and_routes_to_correlate():
    reg = _registry(
        session_history=_records(2, "sess"),
        actor_profile=_records(1, "prof"),
    )
    state = _state()

    route = gather_context(state, reg)

    assert route == ROUTE_CORRELATE
    assert len(state.context) == 3
    record_ids = {e.auditRecordId for e in state.context}
    assert record_ids == {"sess-0", "sess-1", "prof-0"}
    # Every gathered element is record-id-tagged and untrusted (R5.4, R12.1).
    for item in state.context:
        assert item.auditRecordId
        assert item.sourceContentUntrusted is True
    assert state.gaps == []


def test_session_history_keyed_by_alert_id_and_profile_by_actor_id():
    backend = StaticEngineBackend(
        session_history=_records(1, "sess"),
        actor_profile=_records(1, "prof"),
    )
    reg = build_registry(backend, runner=InlineTimeoutRunner())

    gather_context(_state(), reg)

    calls = dict((name, kwargs) for name, kwargs in backend.calls)
    assert calls["get_session_history"] == {"alert_id": "alert-1"}
    assert calls["get_actor_profile"] == {"actor_id": "actor-1"}


# --- gap-on-failure preserves already-gathered context (R5.3) -------------


def test_actor_profile_no_data_records_gap_and_preserves_session_context():
    reg = _registry(
        session_history=_records(2, "sess"),
        actor_profile=[],  # no data -> gap
    )
    state = _state()

    route = gather_context(state, reg)

    assert route == ROUTE_CORRELATE
    # Session context preserved despite the actor-profile shortfall.
    assert len(state.context) == 2
    assert len(state.gaps) == 1
    gap = state.gaps[0]
    assert gap.element == "get_actor_profile"
    assert gap.stage == "context"
    assert "no data" in gap.reason.lower()


def test_session_history_error_records_gap_and_still_gathers_actor_profile():
    class Boom(StaticEngineBackend):
        def get_session_history(self, alert_id: str):
            raise RuntimeError("engine down")

    backend = Boom(actor_profile=_records(1, "prof"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state()

    gather_context(state, reg)

    # Actor profile still gathered even though session history errored.
    assert len(state.context) == 1
    assert state.context[0].auditRecordId == "prof-0"
    assert any(
        g.element == "get_session_history" and "error" in g.reason.lower()
        for g in state.gaps
    )


def test_timeout_on_session_history_is_recorded_as_gap():
    class AlwaysTimeout(InlineTimeoutRunner):
        def run(self, func, timeout_seconds):
            raise ToolTimeout("simulated overrun")

    backend = StaticEngineBackend(
        session_history=_records(1, "sess"),
        actor_profile=_records(1, "prof"),
    )
    reg = ReadToolRegistry(backend, runner=AlwaysTimeout())
    state = _state()

    gather_context(state, reg)

    assert state.context == []  # both calls time out
    assert {g.element for g in state.gaps} == {
        "get_session_history",
        "get_actor_profile",
    }
    assert all("timeout" in g.reason.lower() for g in state.gaps)


# --- unbindable records excluded (R5.5) -----------------------------------


def test_unbindable_context_excluded_and_recorded_as_partial_gap():
    records = [
        EngineRecord(auditRecordId="sess-0", summary="kept"),
        EngineRecord(auditRecordId="", summary="unbindable"),
    ]
    reg = _registry(session_history=records, actor_profile=_records(1, "prof"))
    state = _state()

    gather_context(state, reg)

    # Only the bindable session record + the actor profile survive.
    kept_ids = {e.auditRecordId for e in state.context}
    assert kept_ids == {"sess-0", "prof-0"}
    # The unbindable record is recorded as a partial gap on session history.
    assert any(
        g.element == "get_session_history" and "record id" in g.reason.lower()
        for g in state.gaps
    )


# --- engine-wide triggers with no actorId (R5.2/R5.3) ---------------------


def test_missing_actor_id_records_gap_without_crashing():
    reg = _registry(session_history=_records(1, "sess"), actor_profile=_records(1, "prof"))
    state = _state(_envelope(actor_id=None))

    route = gather_context(state, reg)

    assert route == ROUTE_CORRELATE
    # Session context still gathered.
    assert len(state.context) == 1
    assert state.context[0].auditRecordId == "sess-0"
    # A gap is recorded for the actor profile; the tool is not invoked.
    assert len(state.gaps) == 1
    gap = state.gaps[0]
    assert gap.element == "get_actor_profile"
    assert gap.reason == MISSING_ACTOR_ID_REASON


def test_missing_actor_id_does_not_invoke_actor_profile_tool():
    backend = StaticEngineBackend(
        session_history=_records(1, "sess"),
        actor_profile=_records(1, "prof"),
    )
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state(_envelope(actor_id=None))

    gather_context(state, reg)

    invoked = {name for name, _ in backend.calls}
    assert "get_actor_profile" not in invoked
    assert "get_session_history" in invoked
