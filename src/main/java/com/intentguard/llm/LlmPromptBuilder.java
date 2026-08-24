package com.intentguard.llm;

import java.util.Comparator;
import java.util.List;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.ComponentResult;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;

/**
 * Builds the structured prompts sent to Gemini for Semantic_Inconsistency scoring and Explanation
 * generation. Kept as a pure class with no SDK dependency so the prompt contract can be
 * unit-tested directly (the semantic prompt must carry the intent, command, context, and actor
 * type; the explanation prompt must carry the top contributing components, the decision, and the
 * pasted/typed origin).
 */
public final class LlmPromptBuilder {

    /** How many of the highest-contributing components to name in the explanation prompt. */
    static final int TOP_CONTRIBUTORS = 3;

    private LlmPromptBuilder() {
    }

    /**
     * Builds the semantic-scoring prompt (Req 6.1). Instructs Gemini to return only the JSON object
     * {@code {"semantic_inconsistency": <0.0-1.0>, "rationale": "<short>"}}.
     */
    public static String semanticPrompt(CommandEvent event, String intentText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IntentGuard, a semantic firewall. Judge how far the command's likely "
                + "effect DIVERGES from the user's declared intent.\n");
        sb.append("Return a score in [0.0, 1.0] where 0.0 means fully consistent with the intent "
                + "and 1.0 means completely off-intent.\n\n");
        sb.append("Declared_Intent: ").append(nullSafe(intentText)).append('\n');
        sb.append("Command: ").append(nullSafe(event.commandText())).append('\n');
        sb.append("Working_Directory: ").append(nullSafe(event.cwd())).append('\n');
        sb.append("Repository: ").append(event.repo() == null ? "(none)" : event.repo()).append('\n');
        sb.append("Actor_Type: ").append(event.actorType()).append('\n');
        if (!event.envContext().isEmpty()) {
            sb.append("Environment: ").append(event.envContext()).append('\n');
        }
        sb.append('\n');
        sb.append("Respond with ONLY a JSON object of exactly this shape and nothing else:\n");
        sb.append("{\"semantic_inconsistency\": <0.0-1.0>, \"rationale\": \"<short>\"}");
        return sb.toString();
    }

    /**
     * Builds the explanation prompt for an {@code ask}/{@code block} decision (Req 8.1, 8.2). Names
     * the top contributing components (by applied contribution) and states the pasted/typed origin.
     */
    public static String explanationPrompt(CommandEvent event, DivergenceResult result, Decision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IntentGuard, a semantic firewall. Explain in one or two plain-English "
                + "sentences why the following command was flagged.\n\n");
        sb.append("Decision: ").append(decision.action())
                .append(" (divergence score ").append(format(decision.score())).append(")\n");
        sb.append("Command: ").append(nullSafe(event.commandText())).append('\n');
        sb.append("Working_Directory: ").append(nullSafe(event.cwd())).append('\n');
        sb.append("Input_Origin: ").append(event.inputOrigin()).append('\n');
        sb.append("Actor_Type: ").append(event.actorType()).append('\n');
        sb.append("Top contributing risk components:\n");
        for (ComponentResult component : topContributors(result)) {
            sb.append("  - ").append(component.id())
                    .append(": score=").append(component.score().isPresent()
                            ? format(component.score().getAsDouble()) : "excluded")
                    .append(", weight=").append(format(component.weight()));
            if (component.note() != null && !component.note().isBlank()) {
                sb.append(" (").append(component.note()).append(')');
            }
            sb.append('\n');
        }
        sb.append("\nName the components that contributed most");
        if (event.isPasted()) {
            sb.append(" and state that the command was pasted rather than typed");
        }
        sb.append(". Respond with only one or two sentences.");
        return sb.toString();
    }

    /**
     * Builds the Inferred_Intent summarization prompt (Req 14.1). Instructs Gemini to summarize a
     * recent command window into a single short natural-language goal, returned as plain text.
     */
    public static String summarizeIntentPrompt(List<String> recentCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are IntentGuard, a semantic firewall. Infer, in one short phrase, the most "
                + "likely goal a user is pursuing given their recent commands.\n");
        sb.append("This is a best-effort estimate used only when the user has not declared a "
                + "goal.\n\n");
        sb.append("Recent_Commands:\n");
        if (recentCommands != null) {
            for (String command : recentCommands) {
                sb.append("  - ").append(nullSafe(command)).append('\n');
            }
        }
        sb.append("\nRespond with ONLY a short natural-language phrase describing the inferred "
                + "goal, and nothing else.");
        return sb.toString();
    }

    /**
     * Returns the components ordered by their applied contribution (score times weight), highest
     * first, limited to {@link #TOP_CONTRIBUTORS}. Excluded components contribute nothing and sort
     * last.
     */
    static List<ComponentResult> topContributors(DivergenceResult result) {
        return result.components().stream()
                .sorted(Comparator.comparingDouble(LlmPromptBuilder::contribution).reversed())
                .limit(TOP_CONTRIBUTORS)
                .toList();
    }

    private static double contribution(ComponentResult component) {
        return component.score().isPresent() ? component.score().getAsDouble() * component.weight() : 0.0;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
