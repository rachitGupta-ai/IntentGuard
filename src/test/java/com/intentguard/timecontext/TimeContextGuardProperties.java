package com.intentguard.timecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.intentguard.domain.Actor;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Feature: intentguard-guardrails, Property 25: Time and context guardrails hold risky off-window
 * and out-of-context actions (Stretch).
 *
 * <p>For any current time (evaluated in the configured time zone) outside every approved
 * MaintenanceWindow — with the window start and end instants treated as inclusive — and a
 * Divergence_Score at or above the maintenance-window risk threshold, the Corrective_Action floor
 * is at least ASK; for any event matching a context-mismatch rule, the effective Divergence_Score
 * is at least the context-mismatch floor; and for any session originating from a source not on the
 * approved list, the floor for that session's events is at least ASK and the source restriction is
 * recorded.
 *
 * <p>Exercises {@link TimeContextGuard} directly with a fixed {@link Clock} and time zone so the
 * off-window / boundary-instant behaviour is deterministic and reproducible.
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4.
 */
class TimeContextGuardProperties {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalDate FIXED_DATE = LocalDate.of(2024, 1, 15);

    // A single non-wrapping maintenance window [02:00, 03:00] in ZONE. The current time is
    // "off-window" whenever it maps to a local time strictly before 02:00 or strictly after 03:00.
    private static final LocalTime WINDOW_START = LocalTime.of(2, 0);
    private static final LocalTime WINDOW_END = LocalTime.of(3, 0);
    private static final double MAINTENANCE_THRESHOLD = 0.60;
    private static final double CONTEXT_MISMATCH_FLOOR = 0.75;

    // ---- Req 7.1: off-window + score >= threshold => ASK floor -----------------------------------

    @Property(tries = 200)
    void offWindowRiskyEventRaisesFloorToAsk(
            @ForAll("offWindowLocalTimes") LocalTime offWindowTime,
            @ForAll @DoubleRange(min = MAINTENANCE_THRESHOLD, max = 1.0) double score) {

        Instant now = FIXED_DATE.atTime(offWindowTime).atZone(ZONE).toInstant();
        TimeContextGuard guard = guardAt(now);

        TimeContextContribution c =
                guard.evaluate(benignEvent(), score, maintenanceOnlyConfig(), null);

        // Off-window with a score at/above the threshold holds the action for confirmation (Req 7.1).
        assertThat(c.floor().ordinal()).isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        assertThat(c.triggeredGuardrailIds()).contains(TimeContextGuard.OFF_WINDOW_TRIGGER_ID);
    }

    @Property(tries = 200)
    void withinWindowDoesNotRaiseFloorOnTimeAlone(
            @ForAll("inWindowLocalTimes") LocalTime inWindowTime,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {

        Instant now = FIXED_DATE.atTime(inWindowTime).atZone(ZONE).toInstant();
        TimeContextGuard guard = guardAt(now);

        TimeContextContribution c =
                guard.evaluate(benignEvent(), score, maintenanceOnlyConfig(), null);

        // Within an approved window (inclusive) the maintenance rule never fires, regardless of score.
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(c.triggeredGuardrailIds()).doesNotContain(TimeContextGuard.OFF_WINDOW_TRIGGER_ID);
    }

    @Property(tries = 200)
    void offWindowBelowThresholdDoesNotRaiseFloor(
            @ForAll("offWindowLocalTimes") LocalTime offWindowTime,
            @ForAll @DoubleRange(min = 0.0, max = MAINTENANCE_THRESHOLD, maxIncluded = false)
                    double score) {

        Instant now = FIXED_DATE.atTime(offWindowTime).atZone(ZONE).toInstant();
        TimeContextGuard guard = guardAt(now);

        TimeContextContribution c =
                guard.evaluate(benignEvent(), score, maintenanceOnlyConfig(), null);

        // Off-window but below the risk threshold: the maintenance rule does not fire (Req 7.1).
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(c.triggeredGuardrailIds()).doesNotContain(TimeContextGuard.OFF_WINDOW_TRIGGER_ID);
    }

    // ---- Req 7.1 boundary-instant cases: window start / end are inclusive ------------------------

    @Example
    void windowStartInstantIsInclusive() {
        Instant startInstant = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();
        TimeContextContribution c =
                guardAt(startInstant).evaluate(benignEvent(), 1.0, maintenanceOnlyConfig(), null);
        // The exact start instant is within the window: no maintenance-window raise (Req 7.1).
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ALLOW);
    }

    @Example
    void windowEndInstantIsInclusive() {
        Instant endInstant = FIXED_DATE.atTime(WINDOW_END).atZone(ZONE).toInstant();
        TimeContextContribution c =
                guardAt(endInstant).evaluate(benignEvent(), 1.0, maintenanceOnlyConfig(), null);
        // The exact end instant is within the window: no maintenance-window raise (Req 7.1).
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ALLOW);
    }

    @Example
    void oneNanoBeforeWindowStartIsOffWindow() {
        Instant justBefore =
                FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant().minusNanos(1);
        TimeContextContribution c =
                guardAt(justBefore).evaluate(benignEvent(), MAINTENANCE_THRESHOLD, maintenanceOnlyConfig(), null);
        // One nanosecond before the inclusive start is off-window and holds the risky action.
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(c.triggeredGuardrailIds()).contains(TimeContextGuard.OFF_WINDOW_TRIGGER_ID);
    }

    @Example
    void oneNanoAfterWindowEndIsOffWindow() {
        Instant justAfter = FIXED_DATE.atTime(WINDOW_END).atZone(ZONE).toInstant().plusNanos(1);
        TimeContextContribution c =
                guardAt(justAfter).evaluate(benignEvent(), MAINTENANCE_THRESHOLD, maintenanceOnlyConfig(), null);
        // One nanosecond after the inclusive end is off-window and holds the risky action.
        assertThat(c.floor()).isEqualTo(CorrectiveAction.ASK);
        assertThat(c.triggeredGuardrailIds()).contains(TimeContextGuard.OFF_WINDOW_TRIGGER_ID);
    }

    // ---- Req 7.2: context mismatch => Divergence_Score floor -------------------------------------

    @Property(tries = 200)
    void contextMismatchRaisesScoreFloor(
            @ForAll("mismatchedContexts") String context,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {

        // A "network" command (curl) whose cwd/repo/env never mentions the required "prod-net" token.
        CommandEvent event =
                event("curl http://example.com/data", "/tmp/" + context, context, Map.of("env", context));
        TimeContextConfig config = contextMismatchOnlyConfig();
        // Fixed clock within the window so the maintenance rule cannot interfere.
        Instant now = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();

        TimeContextContribution c = guardAt(now).evaluate(event, score, config, null);

        // The mismatch raises the Divergence_Score to at least the configured floor (Req 7.2).
        assertThat(c.scoreFloor()).isPresent();
        assertThat(c.scoreFloor().getAsDouble()).isGreaterThanOrEqualTo(CONTEXT_MISMATCH_FLOOR);
        assertThat(c.triggeredGuardrailIds())
                .anyMatch(id -> id.startsWith(TimeContextGuard.CONTEXT_MISMATCH_TRIGGER_PREFIX));
    }

    @Property(tries = 200)
    void consistentContextDoesNotRaiseScoreFloor(
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {

        // A "network" command whose repo contains the required "prod-net" token: consistent.
        CommandEvent event =
                event("curl http://example.com/data", "/srv/prod-net", "prod-net-repo", Map.of());
        Instant now = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();

        TimeContextContribution c =
                guardAt(now).evaluate(event, score, contextMismatchOnlyConfig(), null);

        assertThat(c.scoreFloor()).isEmpty();
        assertThat(c.triggeredGuardrailIds())
                .noneMatch(id -> id.startsWith(TimeContextGuard.CONTEXT_MISMATCH_TRIGGER_PREFIX));
    }

    // ---- Req 7.3 / 7.4: unapproved source => ASK floor + recorded --------------------------------

    @Property(tries = 200)
    void unapprovedSourceRaisesFloorToAskAndIsRecorded(
            @ForAll("unapprovedSources") String source,
            @ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {

        Instant now = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();

        TimeContextContribution c =
                guardAt(now).evaluate(benignEvent(), score, sourceRestrictionOnlyConfig(), source);

        // An unapproved source holds the session's events for confirmation and is recorded (Req 7.3, 7.4).
        assertThat(c.floor().ordinal()).isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
        assertThat(c.sourceRestricted()).isTrue();
        assertThat(c.triggeredGuardrailIds())
                .anyMatch(id -> id.startsWith(TimeContextGuard.SOURCE_RESTRICTION_TRIGGER_PREFIX));
    }

    @Property(tries = 200)
    void approvedSourceDoesNotRaiseFloor(@ForAll @DoubleRange(min = 0.0, max = 1.0) double score) {
        Instant now = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();

        TimeContextContribution c =
                guardAt(now).evaluate(benignEvent(), score, sourceRestrictionOnlyConfig(), "office-vpn");

        assertThat(c.floor()).isEqualTo(CorrectiveAction.ALLOW);
        assertThat(c.sourceRestricted()).isFalse();
    }

    @Property(tries = 200)
    void sourceFromEnvContextIsHonored(@ForAll("unapprovedSources") String source) {
        Instant now = FIXED_DATE.atTime(WINDOW_START).atZone(ZONE).toInstant();
        CommandEvent event = event("ls", "/tmp", null, Map.of(TimeContextGuard.SOURCE_ENV_KEY, source));

        // The single-argument overload reads the source from envContext under the "source" key.
        TimeContextContribution c =
                guardAt(now).evaluate(event, 0.1, sourceRestrictionOnlyConfig());

        assertThat(c.sourceRestricted()).isTrue();
        assertThat(c.floor().ordinal()).isGreaterThanOrEqualTo(CorrectiveAction.ASK.ordinal());
    }

    // ---- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<LocalTime> offWindowLocalTimes() {
        // Local times strictly before WINDOW_START or strictly after WINDOW_END, at second precision.
        Arbitrary<Integer> secondsOfDay = Arbitraries.integers().between(0, 86_399);
        return secondsOfDay
                .map(s -> LocalTime.ofSecondOfDay(s))
                .filter(t -> t.isBefore(WINDOW_START) || t.isAfter(WINDOW_END));
    }

    @Provide
    Arbitrary<LocalTime> inWindowLocalTimes() {
        // Local times within [WINDOW_START, WINDOW_END] inclusive, at second precision.
        int start = WINDOW_START.toSecondOfDay();
        int end = WINDOW_END.toSecondOfDay();
        return Arbitraries.integers().between(start, end).map(LocalTime::ofSecondOfDay);
    }

    @Provide
    Arbitrary<String> mismatchedContexts() {
        // Context tokens that never contain the required "prod-net" allowed token.
        return Arbitraries.of("staging", "dev", "sandbox", "qa", "local", "test-area");
    }

    @Provide
    Arbitrary<String> unapprovedSources() {
        // Sources that are never equal (case-insensitively) to the approved "office-vpn".
        Arbitrary<String> named =
                Arbitraries.of("public-wifi", "unknown-host", "tor-exit", "home-net", "203.0.113.7");
        Arbitrary<String> random =
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(12)
                        .filter(s -> !s.equalsIgnoreCase("office-vpn"));
        return Arbitraries.oneOf(named, random);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static TimeContextGuard guardAt(Instant instant) {
        return new TimeContextGuard(Clock.fixed(instant, ZONE));
    }

    private static TimeContextConfig maintenanceOnlyConfig() {
        return new TimeContextConfig(
                ZONE,
                List.of(new MaintenanceWindow(WINDOW_START, WINDOW_END)),
                MAINTENANCE_THRESHOLD,
                List.of(),
                CONTEXT_MISMATCH_FLOOR,
                Set.of());
    }

    private static TimeContextConfig contextMismatchOnlyConfig() {
        return new TimeContextConfig(
                ZONE,
                List.of(),
                MAINTENANCE_THRESHOLD,
                List.of(new ContextMismatchRule("network-in-prod", "network", List.of("prod-net"))),
                CONTEXT_MISMATCH_FLOOR,
                Set.of());
    }

    private static TimeContextConfig sourceRestrictionOnlyConfig() {
        return new TimeContextConfig(
                ZONE,
                List.of(),
                MAINTENANCE_THRESHOLD,
                List.of(),
                CONTEXT_MISMATCH_FLOOR,
                Set.of("office-vpn"));
    }

    private static CommandEvent benignEvent() {
        return event("ls -la", "/home/dev", null, Map.of());
    }

    private static CommandEvent event(String commandText, String cwd, String repo, Map<String, String> env) {
        return new CommandEvent(
                "evt-1",
                Actor.human("dev"),
                "sess-1",
                commandText,
                cwd,
                repo,
                env,
                Instant.now().toEpochMilli(),
                null,
                null,
                null,
                null);
    }
}
