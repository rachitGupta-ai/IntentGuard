/**
 * Stretch velocity/rate guardrail (Requirement 5).
 *
 * <p>This package is self-contained: it does not modify the shared
 * {@code GuardrailContext}, {@code GuardrailDecisionEngine}, or {@code PipelineDecisionProvider}.
 * The {@link com.intentguard.velocity.VelocityGuard} is a feature-flagged {@code @Component}
 * (absent by default) that evaluates a {@link com.intentguard.domain.CommandEvent} and returns a
 * {@link com.intentguard.velocity.VelocityResult} carrying its contribution to the guardrail chain:
 * a Corrective_Action floor, an optional Divergence_Score floor, a session-anomaly flag, and the
 * identifiers of the guardrails that triggered (for audit and explanation naming).
 */
package com.intentguard.velocity;
