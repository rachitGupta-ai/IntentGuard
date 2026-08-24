/**
 * Explanation Generator (Req 8). Produces the plain-English {@code Explanation} attached to every
 * {@code ask}/{@code block} decision.
 *
 * <p>The generator prefers the LLM_Service text and falls back to a deterministic template derived
 * from the ranked component contributions when the LLM is unavailable (timeout/error). Either way
 * the resulting Explanation names the divergence components that contributed most to the decision
 * (Req 8.2) and states the pasted origin when a pasted event contributed (Req 9.3).
 */
package com.intentguard.explanation;
