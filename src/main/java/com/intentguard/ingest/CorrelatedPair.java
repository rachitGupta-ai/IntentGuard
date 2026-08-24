package com.intentguard.ingest;

import java.util.Objects;

import com.intentguard.domain.CommandEvent;

/**
 * A matched Shell_Hook record and Audit_Feed event describing the same command (Req 2.3).
 *
 * @param hookEvent   the pre-execution Shell_Hook event
 * @param auditEvent  the post-execution Audit_Feed event
 * @param correlated  the hook event promoted to {@code signalSource=CORRELATED}
 */
public record CorrelatedPair(CommandEvent hookEvent, CommandEvent auditEvent, CommandEvent correlated) {

    public CorrelatedPair {
        Objects.requireNonNull(hookEvent, "hookEvent must not be null");
        Objects.requireNonNull(auditEvent, "auditEvent must not be null");
        Objects.requireNonNull(correlated, "correlated must not be null");
    }
}
