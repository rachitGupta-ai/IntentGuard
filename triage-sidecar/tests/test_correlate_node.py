"""Unit tests for the ``correlate`` Investigation_Graph node (task 11.1, R6).

These exercise the node against the deterministic ``StaticEngineBackend`` +
``InlineTimeoutRunner`` doubles, covering: correlated signals recorded with
their record ids and appended to ``state.correlations`` (R6.1, R6.2, R6.4),
actor/time-bounded ``query_audit_history`` invocation (R6.3), gap-on-no-data /
error and continuation (R6.5), graceful handling of a missing ``actorId`` /
``sessionId``, and the unconditional route to ``form_hypotheses``.
"""

from __future__ import annotations

from sidecar.models import AlertEnvelope, TriageState, TriggerType
from sidecar.tools import (
    EngineRecord,
    InlineTimeoutRunner,
    ReadToolRegistry,
    StaticEngineBackend,
    build_registry,
)
from sidecar.triage.nodes.correlate import (
    CORRELATE_NODE,
    FORM_HYPOTHESES_NODE,
    correlate,
    route_after_correlate,
)

_ALERT_TS_MS = 1_700_000_000_000
_MS_PER_DAY = 86_400 * 1000


def _records(n: int, prefix: str) -> list[EngineRecord]:
    return [EngineRecord(auditRecordId=f"{prefix}-{i}", summary=f"{prefix} {i}") for i in range(n)]


def _registry(**backend_kwargs) -> ReadToolRegistry:
    backend = StaticEngineBackend(**backend_kwargs)
    return build_registry(backend, runner=InlineTimeoutRunner())


def _state(
    *,
    actor_id: str | None = "actor-1",
    session_id: str | None = "session-1",
    alert_ts: int = _ALERT_TS_MS,
) -> TriageState:
    envelope = AlertEnvelope(
        schemaVersion="v1",
        alertId="alert-1",
        triggerType=TriggerType.BLOCK_RANGE_DIVERGENCE,
        actorId=actor_id,
        sessionId=session_id,
        alertTimestampMs=alert_ts,
        signalPayload={},
    )
    return TriageState(
        alertId="alert-1",
        envelope=envelope,
        triggerType=envelope.triggerType,
        investigation_started_ms=alert_ts,
    )


# --- happy path: all three sources correlate (R6.1, R6.2, R6.3, R6.4) -----


def test_correlations_recorded_with_record_ids_and_no_gaps():
    reg = _registry(
        related_alerts=_records(2, "rel"),
        exfil_correlations=_records(3, "exf"),
        audit_history=_records(1, "aud"),
    )
    state = _state()

    result = correlate(state, reg)

    assert result is state
    assert len(state.correlations) == 6
    # every correlated signal carries its Audit_History record id (R6.4)
    for item in state.correlations:
        assert item.auditRecordId
        assert item.kind == "correlation"
        assert item.sourceContentUntrusted is True
    assert state.gaps == []


def test_query_audit_history_scoped_to_actor_and_30_day_window():
    backend = StaticEngineBackend(
        related_alerts=_records(1, "rel"),
        exfil_correlations=_records(1, "exf"),
        audit_history=_records(1, "aud"),
    )
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state()

    correlate(state, reg)

    audit_calls = [c for c in backend.calls if c[0] == "query_audit_history"]
    assert len(audit_calls) == 1
    _, kwargs = audit_calls[0]
    assert kwargs["actor_id"] == "actor-1"
    assert kwargs["to_ms"] == _ALERT_TS_MS
    assert kwargs["to_ms"] - kwargs["from_ms"] <= 30 * _MS_PER_DAY


# --- gap-on-failure and continuation (R6.5) -------------------------------


def test_no_data_from_each_tool_records_gaps_and_continues():
    reg = _registry()  # all sources empty -> each tool yields a no-data gap
    state = _state()

    correlate(state, reg)

    assert state.correlations == []
    gap_elements = {g.element for g in state.gaps}
    assert gap_elements == {
        "get_related_alerts",
        "get_exfil_correlations",
        "query_audit_history",
    }
    for gap in state.gaps:
        assert gap.stage == "correlate"


def test_backend_error_is_converted_to_gap_and_does_not_raise():
    class Boom(StaticEngineBackend):
        def get_related_alerts(self, actor_id, session_id):
            raise RuntimeError("engine unavailable")

    backend = Boom(
        exfil_correlations=_records(1, "exf"),
        audit_history=_records(1, "aud"),
    )
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state()

    correlate(state, reg)

    # exfil + audit still correlate; related-alerts failure becomes a gap
    assert len(state.correlations) == 2
    error_gaps = [g for g in state.gaps if g.element == "get_related_alerts"]
    assert len(error_gaps) == 1
    assert "error" in error_gaps[0].reason.lower()


def test_partial_result_keeps_bound_evidence_and_records_shortfall():
    records = [
        EngineRecord(auditRecordId="ok-1", summary="kept"),
        EngineRecord(auditRecordId="", summary="unbindable"),
    ]
    reg = _registry(related_alerts=records)
    state = _state()

    correlate(state, reg)

    kept = [e for e in state.correlations if e.auditRecordId == "ok-1"]
    assert len(kept) == 1
    partial_gaps = [
        g
        for g in state.gaps
        if g.element == "get_related_alerts" and "record id" in g.reason.lower()
    ]
    assert len(partial_gaps) == 1


# --- graceful handling of missing actor/session ---------------------------


def test_missing_session_id_records_correlation_gaps_but_still_queries_audit():
    backend = StaticEngineBackend(audit_history=_records(1, "aud"))
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state(session_id=None)

    correlate(state, reg)

    # related/exfil cannot be scoped without a session -> gaps, no crash
    gap_elements = {g.element for g in state.gaps}
    assert "get_related_alerts" in gap_elements
    assert "get_exfil_correlations" in gap_elements
    # audit history only needs the actor, so it still runs and correlates
    assert any(c[0] == "query_audit_history" for c in backend.calls)
    assert len(state.correlations) == 1
    assert not any(c[0] == "get_related_alerts" for c in backend.calls)


def test_missing_actor_id_records_gaps_for_all_three_tools():
    backend = StaticEngineBackend(
        related_alerts=_records(2, "rel"),
        audit_history=_records(2, "aud"),
    )
    reg = build_registry(backend, runner=InlineTimeoutRunner())
    state = _state(actor_id=None)

    correlate(state, reg)

    gap_elements = {g.element for g in state.gaps}
    assert gap_elements == {
        "get_related_alerts",
        "get_exfil_correlations",
        "query_audit_history",
    }
    # no tool ran without an actor
    assert backend.calls == []
    assert state.correlations == []


# --- routing (R6 -> R7) ---------------------------------------------------


def test_route_after_correlate_always_proceeds_to_form_hypotheses():
    state = _state()
    assert route_after_correlate(state) == FORM_HYPOTHESES_NODE
    assert CORRELATE_NODE == "correlate"
    assert FORM_HYPOTHESES_NODE == "form_hypotheses"


def test_correlate_preserves_existing_correlations_and_gaps():
    reg = _registry(related_alerts=_records(1, "rel"))
    state = _state()
    # seed pre-existing state that must be preserved
    correlate(state, reg)
    first_count = len(state.correlations)
    correlate(state, reg)
    assert len(state.correlations) == first_count + 1
