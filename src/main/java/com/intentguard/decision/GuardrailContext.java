package com.intentguard.decision;

import java.util.Objects;

import com.intentguard.blastradius.BlastRadiusResult;
import com.intentguard.policy.PolicyDecision;

/**
 * The additive guardrail inputs the {@link GuardrailDecisionEngine} composes around the existing
 * threshold/clamp/containment decision: the {@link PolicyDecision} for the active CommandPolicy, the
 * {@link BlastRadiusResult} of the blast-radius / protected-target guards, whether an Agent_Actor
 * event fell within its configured capability scope (Req 4.8), and the {@link DualControlStatus} of
 * the event (Req 4).
 *
 * <p>This is passed to the additive {@code decide(..., GuardrailContext)} overload so existing
 * callers of the {@link DecisionEngine#decide} interface method keep compiling unchanged; those
 * callers implicitly use {@link #empty()} (a no-op context that leaves the delegate decision
 * untouched).
 *
 * @param policy               the result of evaluating the active CommandPolicy against the event
 * @param blastRadius          the result of the blast-radius / protected-target guards
 * @param withinCapabilityScope {@code true} unless an Agent_Actor event fell outside its configured
 *                             capability scope, in which case the floor is raised to {@code ASK}
 * @param dualControl          the dual-control state of the event
 */
public record GuardrailContext(
        PolicyDecision policy,
        BlastRadiusResult blastRadius,
        boolean withinCapabilityScope,
        DualControlStatus dualControl) {

    public GuardrailContext {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(blastRadius, "blastRadius must not be null");
        Objects.requireNonNull(dualControl, "dualControl must not be null");
    }

    /**
     * A no-op context: no policy match, no blast-radius trigger, within capability scope, and no
     * dual-control requirement. Used by the retained {@link DecisionEngine#decide} interface method
     * so the guardrail chain reduces exactly to the delegate's decision.
     */
    public static GuardrailContext empty() {
        return new GuardrailContext(
                PolicyDecision.none(),
                BlastRadiusResult.none(),
                true,
                DualControlStatus.NONE);
    }
}
