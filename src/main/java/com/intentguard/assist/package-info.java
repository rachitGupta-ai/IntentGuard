/**
 * NL Operations Assistant — AI-powered natural-language to scored shell command alternatives.
 *
 * <p>This package provides an interactive workflow where operators describe desired actions in
 * natural language (any of the 11 supported Indian languages or English), receive 2–3 generated
 * shell command alternatives with explanations, and select one for divergence scoring before
 * optional execution.
 *
 * <p>Integrates with IntentGuard's existing safety infrastructure: {@code ScoringPipeline},
 * {@code DecisionEngine}, and {@code BlastRadiusGuard} ensure every generated command is evaluated
 * against the operator's behavioral profile and declared intent before execution is permitted.
 */
package com.intentguard.assist;
