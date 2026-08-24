package com.intentguard.decision;

/**
 * Canonical {@code recordType} string values written to the Audit_History
 * ({@link com.intentguard.persistence.AuditHistoryDocument#getRecordType()}) by the guardrail layer
 * (Req 2.10, 3.7, 4.9).
 *
 * <p>The {@code AuditHistoryDocument} schema is intentionally left unchanged: its {@code recordType}
 * is a free-form string, so these constants simply give the guardrail pipeline (task 7.x) a single
 * source of truth for the enumerated values it persists. The values mirror the record types already
 * emitted by {@link com.intentguard.dualcontrol.DualControlService} so audit consumers see one
 * consistent vocabulary.
 */
public final class GuardrailRecordTypes {

    /** A CommandPolicy rule matched a Command_Event (Req 2.10). */
    public static final String POLICY_HIT = "POLICY_HIT";

    /** A blast-radius / protected-target guardrail changed a decision (Req 3.7). */
    public static final String BLAST_RADIUS = "BLAST_RADIUS";

    /** A DualControl approval was raised and execution withheld (Req 4.9). */
    public static final String DUAL_CONTROL_REQUEST = "DUAL_CONTROL_REQUEST";

    /** A DualControl approval was resolved (confirmed, rejected, or timed out) (Req 4.9). */
    public static final String DUAL_CONTROL_RESOLVED = "DUAL_CONTROL_RESOLVED";

    private GuardrailRecordTypes() {
    }
}
