package com.intentguard.assist;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Configurable safety filter that removes dangerous commands from generated alternatives.
 *
 * <p>Patterns are loaded from {@code intentguard.assist.blocklist} configuration at construction
 * time and compiled into {@link Pattern} instances for efficient repeated matching. Any generated
 * {@link CommandAlternative} whose command text matches (via {@link java.util.regex.Matcher#find()})
 * at least one blocklist pattern is silently excluded from results.
 *
 * <p>The default blocklist includes patterns for {@code rm -rf /}, {@code mkfs}, {@code rmmod},
 * and {@code modprobe -r} (Req 3.2). Deployers may extend or replace the blocklist via
 * application configuration (Req 3.5).
 *
 * @see AssistProperties#getBlocklist()
 */
@Component
public class GenerationBlocklist {

    private final List<Pattern> patterns;

    public GenerationBlocklist(AssistProperties properties) {
        this.patterns = properties.getBlocklist().stream()
                .map(Pattern::compile)
                .toList();
    }

    /**
     * Filters alternatives, removing any whose command matches a blocklist pattern.
     *
     * @param alternatives the generated alternatives (must not be null)
     * @return a new list with blocked commands removed; may be empty if all are blocked
     */
    public List<CommandAlternative> filter(List<CommandAlternative> alternatives) {
        return alternatives.stream()
                .filter(alt -> patterns.stream().noneMatch(p -> p.matcher(alt.command()).find()))
                .toList();
    }

    /**
     * Tests whether a single command matches any blocklist pattern.
     *
     * @param command the command text to check (must not be null)
     * @return {@code true} if the command is blocked by at least one pattern
     */
    public boolean isBlocked(String command) {
        return patterns.stream().anyMatch(p -> p.matcher(command).find());
    }
}
