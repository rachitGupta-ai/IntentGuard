package com.intentguard.decision;

import java.util.Objects;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.policy.PolicyAction;
import com.intentguard.policy.PolicyDecision;

/**
 * Shared builder logic for the Audit_History records the guardrail layer persists (Req 2.10, 3.7,
 * 4.9). Collecting it here gives both the property tests and the pipeline (task 7.x) one source of
 * truth for how a guardrail hit is recorded, without touching the free-form
 * {@link AuditHistoryDocument} schema.
 *
 * <p>The triggering guardrail identifier (matched {@code PolicyRule} id / {@code ProtectedTarget}
 * id / guardrail name) is carried in the {@code explanation} — which names it per Req 2.11 — while
 * the {@code recordType}, {@code reasonCode}, and {@code correctiveAction} carry the guardrail
 * semantics and the applied action.
 */
public final class GuardrailAuditRecords {

    private GuardrailAuditRecords() {
    }

    /**
     * Builds a {@link GuardrailRecordTypes#POLICY_HIT} Audit_History record for a policy hit,
     * recording the matched {@code PolicyRule} id and the applied Corrective_Action (Req 2.10).
     * The {@code explanation} is expected to name the matched rule id (Req 2.11); callers should
     * pass the text produced by
     * {@link com.intentguard.explanation.ExplanationGenerator#explain(CommandEvent,
     * com.intentguard.domain.DivergenceResult, Decision, java.util.List)} with the rule id.
     *
     * @param event       the Command_Event the policy matched
     * @param policy      the policy decision; its matched rule supplies the id and action
     * @param decision    the resulting Decision (its action and score are recorded)
     * @param explanation the Explanation naming the matched rule id (Req 2.11)
     * @return a populated {@link AuditHistoryDocument} ready to persist
     * @throws IllegalArgumentException if {@code policy} carries no matched rule
     */
    public static AuditHistoryDocument policyHit(
            CommandEvent event, PolicyDecision policy, Decision decision, String explanation) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        PolicyAction action = policy.action().orElseThrow(() ->
                new IllegalArgumentException("policyHit requires a matched PolicyRule"));

        AuditHistoryDocument record = baseRecord(event);
        record.setRecordType(GuardrailRecordTypes.POLICY_HIT);
        record.setReasonCode(reasonCodeFor(action));
        record.setCorrectiveAction(decision.action().name());
        record.setDivergenceScore(decision.score());
        record.setExplanation(explanation);
        return record;
    }

    /** Maps a matched {@link PolicyAction} to its guardrail {@code reasonCode}. */
    private static String reasonCodeFor(PolicyAction action) {
        return switch (action) {
            case DENY -> GuardrailReasonCodes.POLICY_DENY;
            case REQUIRE_CONFIRM -> GuardrailReasonCodes.POLICY_REQUIRE_CONFIRM;
            case ALLOW -> "POLICY_ALLOW";
        };
    }

    /** Copies the Command_Event identity fields shared by every guardrail audit record. */
    private static AuditHistoryDocument baseRecord(CommandEvent event) {
        AuditHistoryDocument record = new AuditHistoryDocument();
        record.setEventId(event.eventId());
        record.setUserId(event.userId());
        record.setActorType(event.actorType().name());
        record.setHumanPrincipalId(event.actor().humanPrincipalId());
        record.setSessionId(event.sessionId());
        record.setCommandText(event.commandText());
        record.setCwd(event.cwd());
        record.setRepo(event.repo());
        record.setTimestamp(event.timestamp());
        return record;
    }
}
