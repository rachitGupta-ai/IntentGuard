package com.intentguard.timecontext;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for the {@link TimeContextGuard} (Req 7). It bundles the three
 * independently-configurable guardrails:
 *
 * <ul>
 *   <li><b>Maintenance windows</b> (Req 7.1): the approved {@link MaintenanceWindow}s, the
 *       {@link ZoneId} the current time is evaluated in, and the {@code maintenanceWindowRiskThreshold}
 *       Divergence_Score at or above which an off-window event is held for confirmation.</li>
 *   <li><b>Context-mismatch rules</b> (Req 7.2): the {@link ContextMismatchRule}s and the
 *       {@code contextMismatchFloor} the Divergence_Score is raised to when a rule is violated.</li>
 *   <li><b>Geo/source restriction</b> (Req 7.3, 7.4): the {@code approvedSources} allow-list a
 *       session's source must appear on.</li>
 * </ul>
 *
 * <p>A guardrail is inert when its collection is empty: no maintenance windows means the
 * maintenance-window rule never fires (there is no notion of "off-window"); no rules means no
 * context-mismatch raise; an empty {@code approvedSources} means the source restriction is disabled
 * (all sources are permitted). This mirrors the {@code WHERE ... configured / enabled} preconditions
 * of Requirement 7.
 *
 * @param zone                            the time zone the current time is evaluated in (Req 7.1)
 * @param maintenanceWindows              the approved maintenance windows (Req 7.1)
 * @param maintenanceWindowRiskThreshold  the Divergence_Score at/above which an off-window event is
 *                                        held for confirmation (Req 7.1)
 * @param contextMismatchRules            the configured context-mismatch rules (Req 7.2)
 * @param contextMismatchFloor            the Divergence_Score floor raised on a context mismatch
 *                                        (Req 7.2)
 * @param approvedSources                 the allow-list of approved session sources (Req 7.3, 7.4)
 */
public record TimeContextConfig(
        ZoneId zone,
        List<MaintenanceWindow> maintenanceWindows,
        double maintenanceWindowRiskThreshold,
        List<ContextMismatchRule> contextMismatchRules,
        double contextMismatchFloor,
        Set<String> approvedSources) {

    public TimeContextConfig {
        Objects.requireNonNull(zone, "zone must not be null");
        maintenanceWindows = maintenanceWindows == null ? List.of() : List.copyOf(maintenanceWindows);
        contextMismatchRules = contextMismatchRules == null ? List.of() : List.copyOf(contextMismatchRules);
        approvedSources = approvedSources == null ? Set.of() : Set.copyOf(approvedSources);
        if (maintenanceWindowRiskThreshold < 0.0 || maintenanceWindowRiskThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "maintenanceWindowRiskThreshold must be within [0.0, 1.0]");
        }
        if (contextMismatchFloor < 0.0 || contextMismatchFloor > 1.0) {
            throw new IllegalArgumentException("contextMismatchFloor must be within [0.0, 1.0]");
        }
    }

    /** Whether any maintenance window is configured (the maintenance-window rule is active). */
    boolean hasMaintenanceWindows() {
        return !maintenanceWindows.isEmpty();
    }
}
