/**
 * CommandPolicy domain model: versioned, ordered command-pattern rules that deterministically
 * deny, gate, or allow Command_Events regardless of the behavioral Divergence_Score (Req 2).
 *
 * <p>A {@link com.intentguard.policy.CommandPolicy} is an immutable, valid-by-construction,
 * versioned list of {@link com.intentguard.policy.PolicyRule}s. Each rule pairs a
 * {@link com.intentguard.policy.PatternKind glob or regex} pattern (matched against the
 * normalized command text and arguments) and an optional
 * {@link com.intentguard.policy.PolicyScope} qualifier with a
 * {@link com.intentguard.policy.PolicyAction} of {@code DENY}, {@code REQUIRE_CONFIRM}, or
 * {@code ALLOW}. First-match-in-list-order selection yields a
 * {@link com.intentguard.policy.PolicyDecision} the guardrail chain consumes.
 */
package com.intentguard.policy;
