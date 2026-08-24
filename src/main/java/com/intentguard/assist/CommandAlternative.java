package com.intentguard.assist;

import java.util.Objects;

/**
 * A single generated shell command with its explanation.
 *
 * @param command     the shell command text
 * @param explanation plain-English description of effect and impact
 * @param index       zero-based index within the alternatives array
 */
public record CommandAlternative(String command, String explanation, int index) {

    public CommandAlternative {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(explanation, "explanation must not be null");
        if (command.isBlank()) throw new IllegalArgumentException("command must not be blank");
        if (explanation.isBlank()) throw new IllegalArgumentException("explanation must not be blank");
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
    }
}
