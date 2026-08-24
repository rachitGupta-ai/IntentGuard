/**
 * Data-exfiltration guardrails (STRETCH, Requirement 6).
 *
 * <p>This package is self-contained and feature-flagged behind
 * {@code intentguard.guardrails.exfiltration.enabled}. It correlates secret/credential access with
 * outbound network egress within an {@code Intent_Session}, escalates unapproved egress, and blocks
 * access to planted canary tokens. It composes with the guardrail chain only by contributing a
 * {@link com.intentguard.domain.CorrectiveAction} floor, a short-circuit {@code BLOCK} signal, and
 * alert flags via {@link com.intentguard.exfil.ExfiltrationContribution}; it does not modify the
 * shared guardrail context, decision engine, or pipeline provider.
 */
package com.intentguard.exfil;
