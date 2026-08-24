package com.intentguard.domain;

/**
 * Identifier for one of the four divergence-scoring components (Req 5).
 *
 * <ul>
 *   <li>{@code SEQUENCE_SURPRISE} - n-gram statistical unexpectedness over command history.</li>
 *   <li>{@code CONTEXT_MISMATCH} - inconsistency with cwd / repo / environment context.</li>
 *   <li>{@code BEHAVIORAL_DEVIATION} - feature distance from the user's Behavioral_Profile.</li>
 *   <li>{@code SEMANTIC_INCONSISTENCY} - LLM-assisted mismatch against the intent (may be
 *       excluded when no intent or the LLM is unavailable).</li>
 * </ul>
 */
public enum ComponentId {
    SEQUENCE_SURPRISE,
    CONTEXT_MISMATCH,
    BEHAVIORAL_DEVIATION,
    SEMANTIC_INCONSISTENCY
}
