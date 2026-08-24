package com.intentguard.timecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.CorrectiveAction;

/**
 * Evaluates the STRETCH time-and-context guardrails for a single Command_Event (Requirement 7).
 *
 * <p>This guard is self-contained: it neither reads nor mutates the shared guardrail context,
 * decision engine, or pipeline. It produces a {@link TimeContextContribution} that a caller may
 * (optionally) fold into the guardrail chain. The guard is only instantiated when the
 * {@code intentguard.guardrails.time-context.enabled} property is {@code true}, so the core ships
 * intact when the flag is off.
 *
 * <p>Three independent rules contribute to the outcome, in increasing specificity:
 * <ul>
 *   <li><b>Maintenance windows</b> (Req 7.1): WHILE the current time — read from the injected
 *       {@link Clock} and evaluated in the {@link TimeContextConfig#zone() configured zone},
 *       inclusive of each window's start/end instants — falls outside <em>every</em> approved
 *       {@link MaintenanceWindow}, IF the event's Divergence_Score is at or above
 *       {@link TimeContextConfig#maintenanceWindowRiskThreshold()}, the Corrective_Action floor is
 *       raised to at least {@code ASK}.</li>
 *   <li><b>Context-mismatch rules</b> (Req 7.2): when the event's command class is inconsistent
 *       with its cwd/repo/env per a configured {@link ContextMismatchRule}, the Divergence_Score
 *       floor is raised to at least {@link TimeContextConfig#contextMismatchFloor()}.</li>
 *   <li><b>Geo/source restriction</b> (Req 7.3, 7.4): when the session originates from a source not
 *       on {@link TimeContextConfig#approvedSources()}, the Corrective_Action floor is raised to at
 *       least {@code ASK} and the source restriction is recorded.</li>
 * </ul>
 *
 * <p>Every trigger records a stable id in
 * {@link TimeContextContribution#triggeredGuardrailIds()} for audit and explanation naming.
 * Evaluation is deterministic: the same clock instant, event, and configuration always yield the
 * same contribution.
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.time-context.enabled", havingValue = "true")
public class TimeContextGuard {

    /** Trigger id recorded when an off-window risky event raises the floor (Req 7.1). */
    public static final String OFF_WINDOW_TRIGGER_ID = "maintenance-window-off-hours";

    /** Prefix of the trigger id recorded when a context-mismatch rule fires (Req 7.2). */
    public static final String CONTEXT_MISMATCH_TRIGGER_PREFIX = "context-mismatch:";

    /** Prefix of the trigger id recorded when an unapproved source is restricted (Req 7.3, 7.4). */
    public static final String SOURCE_RESTRICTION_TRIGGER_PREFIX = "source-restriction:";

    /** The {@code envContext} key consulted for a session source when none is passed explicitly. */
    public static final String SOURCE_ENV_KEY = "source";

    private final Clock clock;

    @Autowired
    public TimeContextGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Evaluates the time/context guardrails, taking the session source from the event's
     * {@code envContext} under the {@value #SOURCE_ENV_KEY} key.
     *
     * @param event            the Command_Event under evaluation, must not be {@code null}
     * @param divergenceScore  the event's Divergence_Score, expected within {@code [0.0, 1.0]}
     * @param config           the active time/context configuration, must not be {@code null}
     * @return the guardrail-facing {@link TimeContextContribution}
     */
    public TimeContextContribution evaluate(
            CommandEvent event, double divergenceScore, TimeContextConfig config) {
        Objects.requireNonNull(event, "event must not be null");
        return evaluate(event, divergenceScore, config, event.envContext().get(SOURCE_ENV_KEY));
    }

    /**
     * Evaluates the time/context guardrails with an explicit session source.
     *
     * @param event            the Command_Event under evaluation, must not be {@code null}
     * @param divergenceScore  the event's Divergence_Score, expected within {@code [0.0, 1.0]}
     * @param config           the active time/context configuration, must not be {@code null}
     * @param source           the session's originating source, or {@code null}/blank if unknown
     * @return the guardrail-facing {@link TimeContextContribution}
     */
    public TimeContextContribution evaluate(
            CommandEvent event, double divergenceScore, TimeContextConfig config, String source) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(config, "config must not be null");

        CorrectiveAction floor = CorrectiveAction.ALLOW;
        OptionalDouble scoreFloor = OptionalDouble.empty();
        boolean sourceRestricted = false;
        Set<String> triggered = new LinkedHashSet<>();

        // 1. Maintenance windows: off-window + score >= threshold => ASK floor (Req 7.1).
        if (config.hasMaintenanceWindows() && !isWithinAnyWindow(config)
                && divergenceScore >= config.maintenanceWindowRiskThreshold()) {
            floor = floor.raiseTo(CorrectiveAction.ASK);
            triggered.add(OFF_WINDOW_TRIGGER_ID);
        }

        // 2. Context-mismatch rules: raise the Divergence_Score floor (Req 7.2).
        for (ContextMismatchRule rule : config.contextMismatchRules()) {
            if (rule.isMismatch(event)) {
                double floorValue = config.contextMismatchFloor();
                scoreFloor = OptionalDouble.of(
                        scoreFloor.isPresent()
                                ? Math.max(scoreFloor.getAsDouble(), floorValue)
                                : floorValue);
                triggered.add(CONTEXT_MISMATCH_TRIGGER_PREFIX + rule.id());
            }
        }

        // 3. Geo/source restriction: unapproved source => ASK floor + record (Req 7.3, 7.4).
        if (isSourceRestricted(config, source)) {
            floor = floor.raiseTo(CorrectiveAction.ASK);
            sourceRestricted = true;
            triggered.add(SOURCE_RESTRICTION_TRIGGER_PREFIX + source.strip());
        }

        return new TimeContextContribution(
                floor, scoreFloor, sourceRestricted, new ArrayList<>(triggered));
    }

    /**
     * Whether the current clock instant, evaluated in the configured zone, falls within at least one
     * approved maintenance window (inclusive of each window's start/end instants, Req 7.1).
     */
    private boolean isWithinAnyWindow(TimeContextConfig config) {
        Instant now = clock.instant();
        LocalTime local = now.atZone(config.zone()).toLocalTime();
        for (MaintenanceWindow window : config.maintenanceWindows()) {
            if (window.contains(local)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the session source restriction is enabled and the given source is not on the
     * approved-source list. The restriction is disabled (returns {@code false}) when no approved
     * sources are configured or when the source is unknown ({@code null}/blank), since Requirement
     * 7.3 restricts a source that "is not on the approved-source list" rather than an absent one.
     */
    private static boolean isSourceRestricted(TimeContextConfig config, String source) {
        Set<String> approved = config.approvedSources();
        if (approved.isEmpty()) {
            return false;
        }
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.strip();
        for (String approvedSource : approved) {
            if (approvedSource != null && approvedSource.strip().equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        return true;
    }
}
