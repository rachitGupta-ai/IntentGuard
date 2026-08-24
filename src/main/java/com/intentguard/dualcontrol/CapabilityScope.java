package com.intentguard.dualcontrol;

import java.util.List;
import java.util.Map;

import com.intentguard.domain.CommandEvent;
import com.intentguard.scoring.CommandNormalizer;

/**
 * Capability-scope check for Agent_Actor Command_Events (Req 4.8).
 *
 * <p>An {@code AGENT} may be restricted to a configured subset of command classes — its
 * <em>capability scope</em> — expressed in {@code GuardrailConfig.capabilityScopes} as a map from
 * agent identity to the list of permitted command classes. When an agent event's command class
 * falls outside its configured scope, the guardrail chain raises the Corrective_Action floor to at
 * least {@code ASK}.
 *
 * <p>The command class is derived deterministically from the command text via
 * {@link CommandNormalizer#category(String)} (for example {@code git ...} → {@code vcs},
 * {@code kubectl ...} → {@code orchestration}), so the check is reproducible.
 *
 * <p>Scoping is opt-in per agent: {@code HUMAN} events are never scope-restricted, and an agent
 * with no configured scope entry is treated as unconstrained (within scope). Only an agent that
 * <em>has</em> a configured scope whose command class is not listed is considered out of scope.
 */
public final class CapabilityScope {

    private CapabilityScope() {
    }

    /**
     * Returns whether {@code event}'s command class is within the acting agent's configured
     * capability scope.
     *
     * @param event            the Command_Event under evaluation
     * @param capabilityScopes per-agent permitted command classes (agent id → command classes);
     *                         may be {@code null} or empty when the feature is not configured
     * @return {@code true} when the event is a {@code HUMAN} event, when the acting agent has no
     *         configured scope, or when the event's command class is within the agent's scope;
     *         {@code false} only when the acting agent has a configured scope that does not include
     *         the event's command class (Req 4.8)
     */
    public static boolean isWithinScope(CommandEvent event, Map<String, List<String>> capabilityScopes) {
        if (event == null || !event.actor().isAgent()) {
            // Capability scope constrains agents only; human events are never scope-restricted.
            return true;
        }
        if (capabilityScopes == null || capabilityScopes.isEmpty()) {
            return true;
        }
        List<String> permitted = capabilityScopes.get(event.userId());
        if (permitted == null) {
            // No scope configured for this specific agent: unconstrained.
            return true;
        }
        String commandClass = CommandNormalizer.category(event.commandText());
        return permitted.contains(commandClass);
    }
}
