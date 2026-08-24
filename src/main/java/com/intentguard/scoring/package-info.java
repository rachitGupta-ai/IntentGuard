/**
 * Scoring Pipeline module. Computes the four divergence components (Sequence_Surprise,
 * Context_Mismatch, Behavioral_Deviation, Semantic_Inconsistency) and the composite
 * Divergence_Score as a renormalized weighted sum, bounded to [0.0, 1.0].
 */
package com.intentguard.scoring;
