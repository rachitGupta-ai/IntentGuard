package com.intentguard.policy;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.intentguard.domain.CommandEvent;

/**
 * A versioned, immutable, valid-by-construction command policy: an ordered list of
 * {@link PolicyRule}s (Req 2.1, 2.14).
 *
 * <p>The compact constructor rejects a version below {@code 1}, a {@code null} rules list, or
 * duplicate rule ids by throwing {@link InvalidCommandPolicyException} (Req 2.12); the rules list
 * may be empty. The stored rules list is defensively copied and made unmodifiable so the policy is
 * immutable after construction.
 *
 * @param version   the monotonically increasing policy version ({@code >= 1})
 * @param rules     the ordered rules (may be empty, never {@code null}, ids unique)
 * @param updatedBy the Administrator who authored this version
 * @param updatedAt UTC epoch millis when this version was accepted
 */
public record CommandPolicy(int version, List<PolicyRule> rules, String updatedBy, long updatedAt) {

    public CommandPolicy {
        if (version < 1) {
            throw new InvalidCommandPolicyException("CommandPolicy version must be >= 1");
        }
        if (rules == null) {
            throw new InvalidCommandPolicyException("CommandPolicy rules must not be null");
        }
        Set<String> seen = new HashSet<>();
        for (PolicyRule rule : rules) {
            if (rule == null) {
                throw new InvalidCommandPolicyException("CommandPolicy rules must not contain null");
            }
            if (!seen.add(rule.id())) {
                throw new InvalidCommandPolicyException(
                        "CommandPolicy has duplicate rule id: " + rule.id());
            }
        }
        rules = List.copyOf(rules);
    }

    /**
     * Selects the first {@link PolicyRule} whose scope and pattern match {@code event} in list
     * order, or empty when none match (Req 2.6).
     *
     * @param event the Command_Event being evaluated
     * @param group the group the event is evaluated under, or {@code null} if unknown
     */
    public Optional<PolicyRule> firstMatch(CommandEvent event, String group) {
        for (PolicyRule rule : rules) {
            if (rule.matches(event, group)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }
}
