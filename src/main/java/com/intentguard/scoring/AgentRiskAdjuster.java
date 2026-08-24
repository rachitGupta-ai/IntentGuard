package com.intentguard.scoring;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.intentguard.domain.ActorType;
import com.intentguard.domain.AgentRiskMarkers;
import com.intentguard.domain.CommandEvent;

/**
 * Agent-risk uplift applied to the composite Divergence_Score (Req 13.5).
 *
 * <p>When an {@code AGENT} Command_Event carries a risk marker — it opens a new outbound network
 * connection, accesses a credential/secret file, or performs a privilege escalation unrelated to
 * the Declared_Intent — the composite score must be <em>raised</em> and must never be lowered. This
 * class computes that adjustment as a deterministic, monotonic uplift toward 1.0.
 *
 * <h2>Adjustment definition</h2>
 * <p>Let {@code base} be the composite computed by the scoring pipeline and {@code n} the number of
 * distinct risk markers present on an {@code AGENT} event. The adjusted composite is
 * <pre>{@code
 *   factor    = min(1.0, n * UPLIFT_PER_MARKER)
 *   adjusted  = base + (1 - base) * factor
 * }</pre>
 * i.e. a fraction {@code factor} of the remaining distance to 1.0 is added.
 *
 * <h2>Guarantees (Property 19)</h2>
 * <ul>
 *   <li><b>Only ever raises.</b> {@code factor >= 0} and {@code (1 - base) >= 0} for
 *       {@code base in [0,1]}, so {@code adjusted >= base}; adding markers only increases
 *       {@code factor}, so more markers never lower the score.</li>
 *   <li><b>Stays in [0,1].</b> {@code adjusted} is a convex blend of {@code base} and {@code 1.0}
 *       (since {@code factor in [0,1]}), hence it lies in {@code [base, 1] ⊆ [0,1]}.</li>
 *   <li><b>Agent-only.</b> For {@code HUMAN} actors (and for {@code AGENT} events with no markers)
 *       the factor is 0, so the composite is returned unchanged.</li>
 *   <li><b>Deterministic.</b> The result depends only on {@code base} and the event's markers —
 *       no wall-clock time, randomness, or iteration order is read.</li>
 * </ul>
 */
@Component
public final class AgentRiskAdjuster {

    /**
     * Uplift contributed per distinct risk marker. Three markers exist; at three markers the
     * combined factor is {@code 3 * 0.25 = 0.75} (below the 1.0 clamp), leaving a monotonic,
     * marker-count-sensitive uplift while keeping headroom.
     */
    static final double UPLIFT_PER_MARKER = 0.25;

    /**
     * Return the composite adjusted for any agent risk markers on the event. For non-agent events,
     * or agent events with no markers, {@code baseComposite} is returned unchanged.
     *
     * @param baseComposite the composite Divergence_Score in [0.0, 1.0]
     * @param event         the Command_Event being scored
     * @return the adjusted composite in [0.0, 1.0], never less than {@code baseComposite}
     */
    public double adjust(double baseComposite, CommandEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (baseComposite < 0.0 || baseComposite > 1.0 || Double.isNaN(baseComposite)) {
            throw new IllegalArgumentException("baseComposite must be in [0.0, 1.0]: " + baseComposite);
        }
        double factor = riskFactor(event);
        if (factor <= 0.0) {
            return baseComposite;
        }
        double adjusted = baseComposite + (1.0 - baseComposite) * factor;
        return clampUnit(adjusted);
    }

    /**
     * The uplift factor in [0,1] for an event: 0 for non-agent events or agent events with no
     * markers, otherwise {@code min(1.0, markerCount * UPLIFT_PER_MARKER)}.
     */
    double riskFactor(CommandEvent event) {
        if (event.actorType() != ActorType.AGENT) {
            return 0.0;
        }
        int markers = markerCount(event.agentRiskMarkers());
        if (markers == 0) {
            return 0.0;
        }
        return Math.min(1.0, markers * UPLIFT_PER_MARKER);
    }

    private static int markerCount(AgentRiskMarkers markers) {
        int count = 0;
        if (markers.opensOutboundConnection()) {
            count++;
        }
        if (markers.accessesSecret()) {
            count++;
        }
        if (markers.privilegeEscalation()) {
            count++;
        }
        return count;
    }

    private static double clampUnit(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }
}
