package com.intentguard.decision;

/**
 * Canonical {@code reasonCode} string values written to the Audit_History
 * ({@link com.intentguard.persistence.AuditHistoryDocument#getReasonCode()}) and carried on a
 * {@link com.intentguard.domain.Decision} by the guardrail layer (Req 2.10, 2.11, 3.7, 4.9).
 *
 * <p>These values are identical to the reason codes {@link GuardrailDecisionEngine} already emits on
 * its {@link com.intentguard.domain.Decision Decisions}; collecting them here gives the pipeline and
 * the explanation layer a single, public source of truth (the engine's own constants are
 * package-private). The {@code AuditHistoryDocument} schema is unchanged: {@code reasonCode} remains
 * a free-form string.
 *
 * <p>{@link #label(String)} maps each reason code to a human-readable phrase so a deterministic
 * Explanation can name <em>why</em> a guardrail acted even when the LLM is unavailable (Req 2.11,
 * 3.7).
 */
public final class GuardrailReasonCodes {

    /** A matching {@code DENY} PolicyRule short-circuited to a block (Req 2.7). */
    public static final String POLICY_DENY = "POLICY_DENY";

    /** A matching {@code REQUIRE_CONFIRM} PolicyRule raised the floor to ask (Req 2.8). */
    public static final String POLICY_REQUIRE_CONFIRM = "POLICY_REQUIRE_CONFIRM";

    /** A blast-radius / protected-target / mass-op / indeterminate floor raised the action (Req 3). */
    public static final String BLAST_RADIUS_ASK = "BLAST_RADIUS_ASK";

    /** A block-on-access protected target short-circuited to a block (Req 3.3). */
    public static final String BLAST_RADIUS_BLOCK_ON_ACCESS = "BLAST_RADIUS_BLOCK_ON_ACCESS";

    /** A destructive-verb match raised the Divergence_Score floor (Req 3.6). */
    public static final String DESTRUCTIVE_VERB = "DESTRUCTIVE_VERB";

    /** A blast radius over the mass-operation limit raised the floor to ask (Req 3.5). */
    public static final String MASS_OP_LIMIT = "MASS_OP_LIMIT";

    /** An indeterminate blast-radius evaluation failed safe to an ask floor (Req 3.8). */
    public static final String BLAST_RADIUS_INDETERMINATE = "BLAST_RADIUS_INDETERMINATE";

    /** A dual-control confirmation is pending; execution is withheld (Req 4.1, 4.2). */
    public static final String DUAL_CONTROL_PENDING = "DUAL_CONTROL_PENDING";

    /** An unconfirmed dual-control request timed out to a block (Req 4.5). */
    public static final String DUAL_CONTROL_TIMEOUT = "DUAL_CONTROL_TIMEOUT";

    /** An Agent_Actor event outside its capability scope raised the floor to ask (Req 4.8). */
    public static final String CAPABILITY_SCOPE = "CAPABILITY_SCOPE";

    private GuardrailReasonCodes() {
    }

    /**
     * Returns a human-readable label describing the guardrail behind {@code reasonCode}, suitable
     * for naming the triggering guardrail in a deterministic Explanation (Req 2.11, 3.7). Falls back
     * to a generic phrase for any unrecognized or {@code null} code so callers never see a raw code
     * or a {@code null}.
     *
     * @param reasonCode the reason code carried on the {@link com.intentguard.domain.Decision}
     * @return a plain-English label naming the guardrail
     */
    public static String label(String reasonCode) {
        if (reasonCode == null) {
            return "a guardrail";
        }
        return switch (reasonCode) {
            case POLICY_DENY -> "a command policy that denies this command";
            case POLICY_REQUIRE_CONFIRM -> "a command policy that requires confirmation";
            case BLAST_RADIUS_ASK -> "a blast-radius or protected-target guardrail";
            case BLAST_RADIUS_BLOCK_ON_ACCESS -> "a block-on-access protected target";
            case DESTRUCTIVE_VERB -> "a destructive-operation guardrail";
            case MASS_OP_LIMIT -> "a mass-operation limit";
            case BLAST_RADIUS_INDETERMINATE -> "an indeterminate blast-radius evaluation";
            case DUAL_CONTROL_PENDING -> "a pending dual-control approval";
            case DUAL_CONTROL_TIMEOUT -> "a dual-control confirmation timeout";
            case CAPABILITY_SCOPE -> "an agent action outside its capability scope";
            default -> "a guardrail";
        };
    }
}
