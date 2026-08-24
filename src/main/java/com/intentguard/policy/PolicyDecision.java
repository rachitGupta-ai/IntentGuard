package com.intentguard.policy;

import java.util.Objects;
import java.util.Optional;

/**
 * The guardrail-facing result of evaluating the active {@link CommandPolicy} against one
 * Command_Event: the {@link PolicyRule} that matched (first-match), or empty when none did.
 *
 * <p>The {@code isDeny}/{@code isRequireConfirm}/{@code isAllow} predicates reflect the matched
 * rule's {@link PolicyAction}; all three are {@code false} for {@link #none()}. {@link #ruleId()}
 * exposes the matched rule id for audit and Explanation naming (Req 2.10, 2.11).
 *
 * @param matched the first matching rule, or empty when no rule matched
 */
public record PolicyDecision(Optional<PolicyRule> matched) {

    public PolicyDecision {
        Objects.requireNonNull(matched, "matched must not be null");
    }

    /** A decision in which no PolicyRule matched. */
    public static PolicyDecision none() {
        return new PolicyDecision(Optional.empty());
    }

    /** Wraps a matched rule as a decision. */
    public static PolicyDecision of(PolicyRule rule) {
        return new PolicyDecision(Optional.of(rule));
    }

    public boolean isDeny() {
        return actionIs(PolicyAction.DENY);
    }

    public boolean isRequireConfirm() {
        return actionIs(PolicyAction.REQUIRE_CONFIRM);
    }

    public boolean isAllow() {
        return actionIs(PolicyAction.ALLOW);
    }

    /** The matched rule id, or empty when no rule matched. */
    public Optional<String> ruleId() {
        return matched.map(PolicyRule::id);
    }

    /** The matched rule's action, or empty when no rule matched. */
    public Optional<PolicyAction> action() {
        return matched.map(PolicyRule::action);
    }

    private boolean actionIs(PolicyAction action) {
        return matched.map(rule -> rule.action() == action).orElse(false);
    }
}
