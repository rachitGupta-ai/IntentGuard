package com.intentguard.timecontext;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.intentguard.domain.CommandEvent;
import com.intentguard.scoring.CommandNormalizer;

/**
 * A configured rule that flags a Command_Event whose command class is inconsistent with its
 * working directory, repository, or environment (Req 7.2).
 *
 * <p>The rule targets a single command {@code category} (as produced by
 * {@link CommandNormalizer#category(String)}). An event of that category is <em>consistent</em>
 * only when at least one of its context values — its {@code cwd}, its {@code repo}, or any of its
 * {@code envContext} values — contains (case-insensitively) one of the rule's
 * {@code allowedContextTokens}. If none of the context values matches any allowed token, the event
 * is a <em>context mismatch</em> and the guard raises the Divergence_Score floor.
 *
 * <p>A rule with an empty {@code allowedContextTokens} list never matches (it cannot be violated),
 * so an operator must configure at least one allowed token for the rule to have any effect.
 *
 * @param id                  a stable identifier used in audit / explanation naming, never blank
 * @param category            the command category this rule constrains, never blank
 * @param allowedContextTokens the context tokens that make an event of {@code category} consistent
 */
public record ContextMismatchRule(String id, String category, List<String> allowedContextTokens) {

    public ContextMismatchRule {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        allowedContextTokens = allowedContextTokens == null ? List.of() : List.copyOf(allowedContextTokens);
    }

    /**
     * Returns whether the given event violates this rule, i.e. its command category matches this
     * rule's {@code category} but none of its context values matches an allowed token (Req 7.2).
     *
     * @param event the Command_Event under evaluation, must not be {@code null}
     * @return {@code true} when the event is a context mismatch for this rule
     */
    public boolean isMismatch(CommandEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String eventCategory = CommandNormalizer.category(event.commandText());
        if (!eventCategory.equalsIgnoreCase(category)) {
            return false;
        }
        if (allowedContextTokens.isEmpty()) {
            return false;
        }
        return !contextMatchesAny(event);
    }

    private boolean contextMatchesAny(CommandEvent event) {
        if (containsAnyToken(event.cwd())) {
            return true;
        }
        if (containsAnyToken(event.repo())) {
            return true;
        }
        for (String value : event.envContext().values()) {
            if (containsAnyToken(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyToken(String contextValue) {
        if (contextValue == null || contextValue.isBlank()) {
            return false;
        }
        String haystack = contextValue.toLowerCase(Locale.ROOT);
        for (String token : allowedContextTokens) {
            if (token != null && !token.isBlank()
                    && haystack.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
