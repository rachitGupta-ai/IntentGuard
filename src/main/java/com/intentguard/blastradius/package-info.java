/**
 * Blast-radius and protected-target guardrails (Req 3).
 *
 * <p>This package holds the blast-radius domain model ({@link com.intentguard.blastradius.TargetKind},
 * {@link com.intentguard.blastradius.ProtectedTarget}, {@link com.intentguard.blastradius.BlastRadius},
 * {@link com.intentguard.blastradius.BlastRadiusResult}) and the tunable
 * {@link com.intentguard.blastradius.GuardrailConfig} consumed by the {@code BlastRadiusGuard} and
 * served, hot-reloadable and versioned, by the guardrail config store.
 */
package com.intentguard.blastradius;
