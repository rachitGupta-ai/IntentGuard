package com.intentguard.domain;

/**
 * The enforcement outcome applied to a {@code CommandEvent} based on its Divergence_Score and the
 * Threshold_Configuration (Req 7).
 *
 * <ul>
 *   <li>{@code ALLOW} - permit the command.</li>
 *   <li>{@code ASK} - require explicit confirmation before the command proceeds.</li>
 *   <li>{@code BLOCK} - prevent the command.</li>
 * </ul>
 *
 * The ordinal ordering ({@code ALLOW} &lt; {@code ASK} &lt; {@code BLOCK}) reflects increasing
 * restrictiveness and is relied on by the Decision Engine's clamps (e.g. learning-state downgrade,
 * agent unauthorized-by-default floor).
 */
public enum CorrectiveAction {
    ALLOW,
    ASK,
    BLOCK;

    /**
     * Returns the more restrictive of two actions, using the ordinal ordering
     * {@code ALLOW < ASK < BLOCK} (Req 1.4). This is the "most restrictive wins" combinator that
     * backs the guardrail Corrective_Action floor model: composing guardrails via {@code max} can
     * never lower restrictiveness.
     *
     * @param a the first action, must not be {@code null}
     * @param b the second action, must not be {@code null}
     * @return whichever of {@code a} and {@code b} is at least as restrictive as the other
     */
    public static CorrectiveAction max(CorrectiveAction a, CorrectiveAction b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Raises this action so it is no less restrictive than {@code floor}, using the ordering
     * {@code ALLOW < ASK < BLOCK} (Req 1.4). Returns this action unchanged when it is already at
     * least as restrictive as {@code floor}; never lowers restrictiveness.
     *
     * @param floor the minimum (least restrictive) action to enforce, must not be {@code null}
     * @return the more restrictive of this action and {@code floor}
     */
    public CorrectiveAction raiseTo(CorrectiveAction floor) {
        return max(this, floor);
    }
}
