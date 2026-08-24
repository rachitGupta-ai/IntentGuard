package com.intentguard.ingest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for {@link Correlator}: hook/audit correlation on user identity + timestamp
 * proximity within the configured window, and hook-bypass flagging of audit-only events
 * (Req 2.3).
 */
class CorrelatorTest {

    private static final long WINDOW = 1_000L;

    private final Correlator correlator = new Correlator();

    private static CommandEvent event(String user, long ts, SignalSource source) {
        return new CommandEvent(
                "id-" + user + "-" + ts,
                Actor.human(user),
                null,
                "cmd",
                "/cwd",
                null,
                Map.of(),
                ts,
                InputOrigin.UNKNOWN,
                source,
                IntentSource.NONE,
                AgentRiskMarkers.none());
    }

    @Test
    void predicateMatchesSameUserWithinWindow() {
        assertThat(correlator.correlates("alice", 1000L, "alice", 1500L, WINDOW)).isTrue();
        assertThat(correlator.correlates("alice", 1000L, "alice", 2000L, WINDOW)).isTrue(); // exactly window
    }

    @Test
    void predicateDoesNotMatchOutsideWindow() {
        assertThat(correlator.correlates("alice", 1000L, "alice", 2001L, WINDOW)).isFalse();
    }

    @Test
    void predicateDoesNotMatchDifferentUsers() {
        assertThat(correlator.correlates("alice", 1000L, "bob", 1000L, WINDOW)).isFalse();
    }

    @Test
    void correlatesHookAndAuditWithinWindow() {
        CommandEvent hook = event("alice", 1000L, SignalSource.HOOK);
        CommandEvent audit = event("alice", 1200L, SignalSource.AUDIT);

        CorrelationResult result = correlator.correlate(List.of(hook), List.of(audit), WINDOW);

        assertThat(result.correlated()).hasSize(1);
        assertThat(result.hookBypasses()).isEmpty();
        assertThat(result.unmatchedHooks()).isEmpty();
        CorrelatedPair pair = result.correlated().get(0);
        assertThat(pair.hookEvent()).isEqualTo(hook);
        assertThat(pair.auditEvent()).isEqualTo(audit);
        assertThat(pair.correlated().signalSource()).isEqualTo(SignalSource.CORRELATED);
    }

    @Test
    void auditOnlyEventWithNoHookIsFlaggedAsHookBypass() {
        CommandEvent audit = event("mallory", 5000L, SignalSource.AUDIT);

        CorrelationResult result = correlator.correlate(List.of(), List.of(audit), WINDOW);

        assertThat(result.correlated()).isEmpty();
        assertThat(result.hookBypasses()).containsExactly(audit);
        assertThat(result.hasHookBypass()).isTrue();
        // A hook bypass remains a pure AUDIT-sourced event.
        assertThat(result.hookBypasses().get(0).signalSource()).isEqualTo(SignalSource.AUDIT);
    }

    @Test
    void auditEventOutsideWindowIsAHookBypassNotACorrelation() {
        CommandEvent hook = event("alice", 1000L, SignalSource.HOOK);
        CommandEvent audit = event("alice", 3000L, SignalSource.AUDIT); // 2000ms apart > window

        CorrelationResult result = correlator.correlate(List.of(hook), List.of(audit), WINDOW);

        assertThat(result.correlated()).isEmpty();
        assertThat(result.hookBypasses()).containsExactly(audit);
        assertThat(result.unmatchedHooks()).containsExactly(hook);
    }

    @Test
    void differentUserAuditEventDoesNotCorrelateWithHook() {
        CommandEvent hook = event("alice", 1000L, SignalSource.HOOK);
        CommandEvent audit = event("bob", 1000L, SignalSource.AUDIT);

        CorrelationResult result = correlator.correlate(List.of(hook), List.of(audit), WINDOW);

        assertThat(result.correlated()).isEmpty();
        assertThat(result.hookBypasses()).containsExactly(audit);
        assertThat(result.unmatchedHooks()).containsExactly(hook);
    }

    @Test
    void eachAuditEventMatchesClosestHookAndConsumesItOnce() {
        CommandEvent hookEarly = event("alice", 1000L, SignalSource.HOOK);
        CommandEvent hookLate = event("alice", 1800L, SignalSource.HOOK);
        CommandEvent audit1 = event("alice", 1100L, SignalSource.AUDIT); // closest to hookEarly
        CommandEvent audit2 = event("alice", 1750L, SignalSource.AUDIT); // closest to hookLate

        CorrelationResult result =
                correlator.correlate(List.of(hookEarly, hookLate), List.of(audit1, audit2), WINDOW);

        assertThat(result.correlated()).hasSize(2);
        assertThat(result.hookBypasses()).isEmpty();
        assertThat(result.unmatchedHooks()).isEmpty();
        assertThat(result.correlated().get(0).hookEvent()).isEqualTo(hookEarly);
        assertThat(result.correlated().get(1).hookEvent()).isEqualTo(hookLate);
    }
}
