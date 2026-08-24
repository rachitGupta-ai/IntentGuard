package com.intentguard.domain;

/**
 * Risk markers observed for an {@code AGENT} Command_Event that must never lower the
 * Divergence_Score and raise it when unrelated to the Declared_Intent (Req 13.5).
 *
 * @param opensOutboundConnection the command opens a new outbound network connection
 * @param accessesSecret          the command accesses a credential or secret file
 * @param privilegeEscalation     the command performs a privilege escalation
 */
public record AgentRiskMarkers(
        boolean opensOutboundConnection,
        boolean accessesSecret,
        boolean privilegeEscalation) {

    private static final AgentRiskMarkers NONE = new AgentRiskMarkers(false, false, false);

    /** No agent risk markers set. */
    public static AgentRiskMarkers none() {
        return NONE;
    }

    /** True when any risk marker is present. */
    public boolean any() {
        return opensOutboundConnection || accessesSecret || privilegeEscalation;
    }
}
