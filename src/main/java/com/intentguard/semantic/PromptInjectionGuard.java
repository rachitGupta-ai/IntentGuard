package com.intentguard.semantic;

import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;

/**
 * Prompt-injection heuristic guard (Req 8.1, 8.2), enabled only when
 * {@code intentguard.guardrails.semantic.enabled=true}.
 *
 * <p>When a Command_Event's command context matches a configured
 * {@link PromptInjectionPattern}, the guard produces a {@link PromptInjectionResult} that raises the
 * Divergence_Score floor to at least {@link SemanticGuardConfig#promptInjectionFloor()} (Req 8.1)
 * and records the matched pattern id for the Audit_History (Req 8.2). Patterns are evaluated in list
 * order and the first match wins so a recorded id is deterministic.
 *
 * <p>The guard is stateless and deterministic: the same event and configuration always yield the
 * same result, and it never treats the absence of a match as a signal.
 */
@Component
@ConditionalOnProperty(name = "intentguard.guardrails.semantic.enabled", havingValue = "true")
public class PromptInjectionGuard {

    /**
     * Evaluates the prompt-injection heuristics against a Command_Event's command context.
     *
     * @param event the Command_Event under evaluation, must not be {@code null}
     * @param cfg   the active semantic-guard configuration, must not be {@code null}
     * @return a {@link PromptInjectionResult} carrying the score floor and matched pattern id on a
     *         match, or {@link PromptInjectionResult#none()} when nothing matched
     */
    public PromptInjectionResult evaluate(CommandEvent event, SemanticGuardConfig cfg) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(cfg, "cfg must not be null");

        String context = commandContext(event);
        for (PromptInjectionPattern pattern : cfg.promptInjectionPatterns()) {
            if (pattern.matches(context)) {
                return PromptInjectionResult.match(pattern.id(), cfg.promptInjectionFloor());
            }
        }
        return PromptInjectionResult.none();
    }

    /**
     * Builds the deterministic command context a prompt-injection pattern is tested against: the
     * command text followed by the environment-context values (sorted by key for stability).
     */
    static String commandContext(CommandEvent event) {
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add(event.commandText());
        Map<String, String> env = event.envContext();
        env.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue() != null) {
                        joiner.add(entry.getValue());
                    }
                });
        return joiner.toString();
    }
}
