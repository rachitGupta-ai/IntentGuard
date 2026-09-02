package com.intentguard.api;

import java.util.List;

import com.intentguard.persistence.AuditHistoryDocument;
import com.intentguard.persistence.ComponentScoreDocument;

/**
 * A rich, human-readable "why did IntentGuard reach this verdict?" projection of a persisted
 * {@link AuditHistoryDocument}. Unlike the lightweight {@link ScoreEvent} pushed on the live
 * channel, this view exposes the full per-component breakdown so an operator (or a jury) can see
 * exactly which signals drove the decision, ranked by their applied contribution.
 *
 * <p>This is the explainability surface that distinguishes IntentGuard from opaque, score-only
 * detectors: every ALLOW/ASK/BLOCK is fully decomposed and attributable.
 *
 * @param eventId          the Command_Event id
 * @param userId           the user (or agent's human principal)
 * @param actorType        HUMAN or AGENT
 * @param commandText      the observed command
 * @param cwd              the working directory
 * @param inputOrigin      TYPED / PASTED / UNKNOWN
 * @param divergenceScore  the composite Divergence_Score in [0,1]
 * @param action           the Corrective_Action (ALLOW / ASK / BLOCK)
 * @param reasonCode       the machine-readable rule that produced the verdict
 * @param profileState     LEARNING or ACTIVE at the time of scoring
 * @param intentPresent    whether a Declared_/Inferred_Intent was scored against
 * @param intentSource     DECLARED / INFERRED / NONE
 * @param explanation      the plain-English (LLM or template) explanation
 * @param topContributor   the single highest-contributing component (or null when none scored)
 * @param components       every component's contribution, ranked highest-first
 */
public record ExplainView(
        String eventId,
        String userId,
        String actorType,
        String commandText,
        String cwd,
        String inputOrigin,
        double divergenceScore,
        String action,
        String reasonCode,
        String profileState,
        boolean intentPresent,
        String intentSource,
        String explanation,
        ComponentContribution topContributor,
        List<ComponentContribution> components) {

    /**
     * A single component's contribution to the composite, expressed both as its raw score/weight
     * and as the applied contribution ({@code score * weight}) that determines its influence.
     *
     * @param component    the component id (e.g. SEMANTIC_INCONSISTENCY)
     * @param score        the component score in [0,1], or null when excluded
     * @param weight       the applied weight before renormalization
     * @param contribution the applied contribution (score * weight), 0 when excluded
     * @param excluded     whether the component was excluded from the composite
     * @param note         exclusion reason or other note, or null
     */
    public record ComponentContribution(
            String component,
            Double score,
            double weight,
            double contribution,
            boolean excluded,
            String note) {
    }

    /** Builds an {@link ExplainView} from a persisted audit record, ranking components by contribution. */
    public static ExplainView from(AuditHistoryDocument doc) {
        List<ComponentContribution> ranked = doc.getComponents().stream()
                .map(ExplainView::toContribution)
                .sorted((a, b) -> Double.compare(b.contribution(), a.contribution()))
                .toList();

        ComponentContribution top = ranked.stream()
                .filter(c -> !c.excluded())
                .findFirst()
                .orElse(null);

        return new ExplainView(
                doc.getEventId(),
                doc.getUserId(),
                doc.getActorType(),
                doc.getCommandText(),
                doc.getCwd(),
                doc.getInputOrigin(),
                doc.getDivergenceScore(),
                doc.getCorrectiveAction(),
                doc.getReasonCode(),
                doc.getProfileState(),
                doc.isIntentPresent(),
                doc.getIntentSource(),
                doc.getExplanation(),
                top,
                ranked);
    }

    private static ComponentContribution toContribution(ComponentScoreDocument c) {
        boolean excluded = c.getScore() == null;
        double contribution = excluded ? 0.0 : c.getScore() * c.getWeight();
        return new ComponentContribution(
                c.getId(),
                c.getScore(),
                c.getWeight(),
                contribution,
                excluded,
                c.getNote());
    }
}
