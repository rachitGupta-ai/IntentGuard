/**
 * Operational and safety hardening guardrails (Requirement 9, Stretch).
 *
 * <p>This package is self-contained and composes with — but does not modify — the core guardrail
 * chain ({@code GuardrailDecisionEngine}, {@code GuardrailContext}, {@code PipelineDecisionProvider}).
 * It provides two independent primitives:
 *
 * <ul>
 *   <li>{@link com.intentguard.hardening.FailClosedGuard} — a fail-closed default-deny helper that
 *       applies a {@code BLOCK} and records the unavailable dependency when a required guardrail
 *       dependency cannot be reached within the configured guardrail decision timeout (Req 9.1, 9.2).</li>
 *   <li>{@link com.intentguard.hardening.PrivilegeSeparationChecker} — a startup check that refuses
 *       the enforcing state and records the failure unless the engine runs under a dedicated service
 *       account distinct from every monitored user (Req 9.3, 9.4).</li>
 * </ul>
 */
package com.intentguard.hardening;
