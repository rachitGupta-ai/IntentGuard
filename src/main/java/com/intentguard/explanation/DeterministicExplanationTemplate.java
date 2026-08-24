package com.intentguard.explanation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import com.intentguard.decision.GuardrailReasonCodes;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentId;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.CorrectiveAction;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;

/**
 * Deterministic, LLM-free {@code Explanation} template (Req 8.4). Given the divergence result and
 * the decision, it composes one or two plain-English sentences that:
 *
 * <ul>
 *   <li>state the Corrective_Action taken ({@code ask}/{@code block});</li>
 *   <li>name the divergence components that contributed most to the decision (Req 8.2), ranked by
 *       contribution ({@code score * appliedWeight}) among the non-excluded components; and</li>
 *   <li>state the pasted origin when the flagged Command_Event was pasted (Req 9.3).</li>
 * </ul>
 *
 * <p>The composition reads only its inputs (no clock, randomness, or iteration-order dependence),
 * so it is fully deterministic: the same inputs always yield the same text. It is separated from
 * the {@link ExplanationGenerator} so the template math can be unit-tested directly without any
 * LLM.
 */
final class DeterministicExplanationTemplate {

    /** How many top contributing components to name. */
    private static final int MAX_NAMED_CONTRIBUTORS = 2;

    private DeterministicExplanationTemplate() {
    }

    /**
     * Compose a non-empty deterministic Explanation from the ranked component contributions.
     *
     * @param event    the flagged Command_Event (its pasted origin is surfaced when applicable)
     * @param result   the divergence result carrying every component score and applied weight
     * @param decision the corrective decision reached (drives the {@code ask}/{@code block} phrasing)
     * @return a plain-English Explanation naming the top contributing components (never empty)
     */
    static String compose(CommandEvent event, DivergenceResult result, Decision decision) {
        List<ComponentId> topContributors = rankContributors(result);
        String actionPhrase = actionPhrase(decision.action());

        StringBuilder text = new StringBuilder();
        text.append("IntentGuard ").append(actionPhrase).append(" this command (divergence score ")
                .append(formatScore(decision.score())).append(").");

        if (!topContributors.isEmpty()) {
            text.append(" The main contributing factor")
                    .append(topContributors.size() > 1 ? "s were " : " was ")
                    .append(joinLabels(topContributors))
                    .append('.');
        }

        // Req 9.3: when a pasted event contributed to the ask/block decision, state the origin.
        if (event.isPasted()) {
            text.append(" The command was pasted rather than typed, which increases risk.");
        }

        return text.toString();
    }

    /**
     * Compose a deterministic Explanation that, in addition to naming the top divergence
     * contributors, <strong>names the triggering guardrail(s)</strong> for an {@code ask}/{@code
     * block} caused by a guardrail (Req 2.11, 3.7). This is the LLM-free guarantee: even when the
     * LLM is unavailable the Explanation still identifies the matched {@code PolicyRule} id,
     * {@code ProtectedTarget} id, or guardrail name that drove the decision.
     *
     * <p>Additive overload: when {@code triggeredGuardrailIds} is {@code null} or empty this is
     * exactly {@link #compose(CommandEvent, DivergenceResult, Decision)}, so existing callers are
     * unaffected.
     *
     * @param event                 the flagged Command_Event
     * @param result                the divergence result
     * @param decision              the corrective decision reached (its reason code labels the
     *                              guardrail)
     * @param triggeredGuardrailIds identifiers of the guardrail(s) that triggered (matched
     *                              PolicyRule / ProtectedTarget ids, or guardrail names)
     * @return a plain-English Explanation naming both the top contributors and the triggering
     *         guardrail(s) (never empty)
     */
    static String compose(
            CommandEvent event,
            DivergenceResult result,
            Decision decision,
            List<String> triggeredGuardrailIds) {
        return withGuardrailNaming(compose(event, result, decision), decision, triggeredGuardrailIds);
    }

    /**
     * Ensures {@code base} names the triggering guardrail(s): if every id is already present the
     * text is returned unchanged (so LLM text that already names them is not padded); otherwise a
     * clause naming the guardrail(s) and the reason label is appended. Deterministic and side-effect
     * free.
     *
     * @param base                  the Explanation text to augment (LLM or deterministic)
     * @param decision              the corrective decision (its reason code selects the label)
     * @param triggeredGuardrailIds the triggering guardrail identifiers
     * @return {@code base} guaranteed to name every triggering guardrail id
     */
    static String withGuardrailNaming(
            String base, Decision decision, List<String> triggeredGuardrailIds) {
        List<String> ids = normalizeIds(triggeredGuardrailIds);
        if (ids.isEmpty()) {
            return base;
        }
        boolean allNamed = ids.stream().allMatch(base::contains);
        if (allNamed) {
            return base;
        }
        String reasonLabel = GuardrailReasonCodes.label(decision.reasonCode());
        return base + " This decision was triggered by " + reasonLabel + " ("
                + String.join(", ", ids) + ").";
    }

    /** De-duplicates (preserving order) and drops null/blank guardrail ids. */
    private static List<String> normalizeIds(List<String> triggeredGuardrailIds) {
        if (triggeredGuardrailIds == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : triggeredGuardrailIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.strip());
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Rank the non-excluded components by their contribution to the composite
     * ({@code score * appliedWeight}), highest first, and return the ids of the top few.
     *
     * <p>When every available contribution is zero, the ranking falls back to the applied weight so
     * that the Explanation still names the components most able to influence the decision. Excluded
     * components (no score) are never named as contributors because they did not contribute.
     */
    static List<ComponentId> rankContributors(DivergenceResult result) {
        List<ComponentResult> available = new ArrayList<>();
        for (ComponentResult component : result.components()) {
            if (!component.isExcluded()) {
                available.add(component);
            }
        }

        available.sort(Comparator
                .comparingDouble(DeterministicExplanationTemplate::contribution)
                .reversed()
                // Tie-break deterministically by component id so output is stable.
                .thenComparing(component -> component.id().name()));

        List<ComponentId> ranked = new ArrayList<>();
        for (ComponentResult component : available) {
            if (ranked.size() >= MAX_NAMED_CONTRIBUTORS) {
                break;
            }
            ranked.add(component.id());
        }
        return ranked;
    }

    private static double contribution(ComponentResult component) {
        return component.score().orElse(0.0) * component.weight();
    }

    private static String joinLabels(List<ComponentId> ids) {
        if (ids.size() == 1) {
            return label(ids.get(0));
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                joined.append(i == ids.size() - 1 ? " and " : ", ");
            }
            joined.append(label(ids.get(i)));
        }
        return joined.toString();
    }

    /** Plain-English label for each divergence component. */
    static String label(ComponentId id) {
        return switch (id) {
            case SEQUENCE_SURPRISE -> "an unusual command sequence for this user";
            case CONTEXT_MISMATCH -> "a mismatch with the working directory, repository, or environment";
            case BEHAVIORAL_DEVIATION -> "a deviation from the user's normal behavior";
            case SEMANTIC_INCONSISTENCY -> "inconsistency with the declared intent";
        };
    }

    private static String actionPhrase(CorrectiveAction action) {
        return switch (action) {
            case BLOCK -> "blocked";
            case ASK -> "asked for confirmation on";
            case ALLOW -> "flagged"; // Explanations are generated only for ask/block; defensive default.
        };
    }

    private static String formatScore(double score) {
        return String.format(java.util.Locale.ROOT, "%.2f", score);
    }
}
