package com.intentguard.policy;

/**
 * The enforcement action a matching {@link PolicyRule} contributes to the guardrail chain (Req 2.1).
 *
 * <ul>
 *   <li>{@code DENY} - a short-circuit block that always yields {@code BLOCK} regardless of the
 *       Divergence_Score and is never softened by the learning clamp (Req 2.7).</li>
 *   <li>{@code REQUIRE_CONFIRM} - raises the Corrective_Action floor to at least {@code ASK},
 *       retaining a {@code BLOCK} if the threshold map already yields one (Req 2.8).</li>
 *   <li>{@code ALLOW} - permits the Command_Event without applying a threshold-map block for it
 *       (Req 2.9).</li>
 * </ul>
 */
public enum PolicyAction {
    DENY,
    REQUIRE_CONFIRM,
    ALLOW
}
