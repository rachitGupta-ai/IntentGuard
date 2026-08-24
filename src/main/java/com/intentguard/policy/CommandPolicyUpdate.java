package com.intentguard.policy;

import java.util.List;
import java.util.Objects;

/**
 * A proposed Administrator update to the active {@link CommandPolicy} (Req 2.2, 2.13).
 *
 * <p>Carries only the tunable field — the ordered list of {@link PolicyRule}s; the {@code version},
 * author, and timestamp are assigned by {@link CommandPolicyService} when a valid update is
 * applied. Mirrors {@link com.intentguard.config.ThresholdConfigUpdate}: the service validates a
 * proposed update by materializing a {@link CommandPolicy} from it (valid by construction) — an
 * invalid proposal is rejected via {@link InvalidCommandPolicyException} and the previously active
 * policy is retained unchanged.
 *
 * @param rules the ordered rules for the proposed policy (may be empty, never {@code null})
 */
public record CommandPolicyUpdate(List<PolicyRule> rules) {

    public CommandPolicyUpdate {
        Objects.requireNonNull(rules, "rules must not be null");
        rules = List.copyOf(rules);
    }

    /** Derives an update carrying the rules of an existing policy. */
    public static CommandPolicyUpdate from(CommandPolicy policy) {
        return new CommandPolicyUpdate(policy.rules());
    }
}
