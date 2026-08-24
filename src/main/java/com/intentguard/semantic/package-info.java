/**
 * Semantic and LLM guardrails (Req 8, STRETCH).
 *
 * <p>This package is a self-contained stretch extension of the IntentGuard guardrail layer. It adds
 * three feature-flagged guards, all gated behind
 * {@code intentguard.guardrails.semantic.enabled=true}, that contribute additional
 * Divergence_Score floors and session-level alerts without modifying the shared
 * {@code GuardrailContext}, {@code GuardrailDecisionEngine}, or {@code PipelineDecisionProvider}:
 *
 * <ul>
 *   <li>{@link com.intentguard.semantic.PromptInjectionGuard} — raises a prompt-injection
 *       Divergence_Score floor and records the matched pattern id when a Command_Event's command
 *       context matches a configured prompt-injection pattern (Req 8.1, 8.2).</li>
 *   <li>{@link com.intentguard.semantic.IntentDriftTracker} — a per-session cumulative
 *       {@code IntentDrift} tracker that raises and records a session-level drift alert once
 *       cumulative drift exceeds the configured threshold (Req 8.3, 8.4).</li>
 *   <li>{@link com.intentguard.semantic.SemanticLlmGuard} — reuses the firewall's
 *       exclude-on-malformed behaviour so a malformed LLM response is excluded from the
 *       Divergence_Score (never treated as a signal) and the malformed-response error is recorded
 *       (Req 8.5, 8.6).</li>
 * </ul>
 */
package com.intentguard.semantic;
