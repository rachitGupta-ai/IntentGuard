package com.intentguard.ingest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.SignalSource;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Feature: intentguard-semantic-firewall, Property 10: Correlation matches on identity and time
 * proximity.
 *
 * <p>For any pair of a Shell_Hook record and an Audit_Feed event, they are correlated if and only
 * if they share the same user identity and their timestamps differ by no more than the configured
 * correlation window (Validates: Requirements 2.3).
 *
 * <p>The property exercises {@link Correlator}'s pairwise predicate directly and mirrors its exact
 * documented semantics: {@code correlated iff sameUser && |t_hook - t_audit| <= window}. The window
 * is treated as <em>inclusive</em> (a difference exactly equal to the window still correlates).
 * User identities are drawn from a small pool so that same-user and different-user pairs both occur
 * frequently across iterations, and both the raw {@code (userId, ts)} overload and the
 * {@link CommandEvent}-based overload are asserted to agree with the biconditional.
 */
class CorrelationProperties {

    private final Correlator correlator = new Correlator();

    @Property(tries = 200)
    void correlationMatchesOnIdentityAndTimeProximity(
            @ForAll("userId") String hookUser,
            @ForAll("userId") String auditUser,
            @ForAll @LongRange(min = 0L, max = 10_000_000L) long hookTs,
            @ForAll @LongRange(min = 0L, max = 10_000_000L) long auditTs,
            @ForAll @LongRange(min = 0L, max = 5_000L) long windowMs) {

        boolean sameUser = hookUser.equals(auditUser);
        boolean withinWindow = Math.abs(hookTs - auditTs) <= windowMs;
        boolean expected = sameUser && withinWindow;

        // Biconditional on the raw predicate.
        boolean actual = correlator.correlates(hookUser, hookTs, auditUser, auditTs, windowMs);
        assertThat(actual)
                .as(
                        "correlates(%s@%d, %s@%d, window=%d): sameUser=%s withinWindow=%s",
                        hookUser, hookTs, auditUser, auditTs, windowMs, sameUser, withinWindow)
                .isEqualTo(expected);

        // The CommandEvent-based overload must agree with the raw predicate.
        CommandEvent hookEvent = event(hookUser, hookTs, SignalSource.HOOK);
        CommandEvent auditEvent = event(auditUser, auditTs, SignalSource.AUDIT);
        assertThat(correlator.correlates(hookEvent, auditEvent, windowMs)).isEqualTo(expected);

        // The batch correlate(...) must reflect the same biconditional for a single pair: a matched
        // pair exists exactly when the predicate holds, otherwise the audit event is a hook bypass.
        CorrelationResult result =
                correlator.correlate(List.of(hookEvent), List.of(auditEvent), windowMs);
        assertThat(result.correlated().isEmpty()).isEqualTo(!expected);
        assertThat(result.hookBypasses().contains(auditEvent)).isEqualTo(!expected);
    }

    @Property(tries = 200)
    void windowBoundaryIsInclusive(
            @ForAll("userId") String user,
            @ForAll @LongRange(min = 0L, max = 10_000_000L) long baseTs,
            @ForAll @LongRange(min = 0L, max = 5_000L) long windowMs) {

        // A difference exactly equal to the window is inclusive and must correlate for the same user.
        long auditTs = baseTs + windowMs;
        assertThat(correlator.correlates(user, baseTs, user, auditTs, windowMs))
                .as("difference == window (%d) must correlate inclusively", windowMs)
                .isTrue();

        // One millisecond beyond the window must not correlate.
        long justOutside = baseTs + windowMs + 1;
        assertThat(correlator.correlates(user, baseTs, user, justOutside, windowMs))
                .as("difference window+1 (%d) must not correlate", windowMs + 1)
                .isFalse();
    }

    @Provide
    Arbitrary<String> userId() {
        // A small pool so same-user and different-user pairings both occur frequently.
        return Arbitraries.of("alice", "bob", "carol", "dave");
    }

    private static CommandEvent event(String user, long ts, SignalSource source) {
        return new CommandEvent(
                "id-" + user + "-" + ts + "-" + source,
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
}
