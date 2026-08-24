package com.intentguard.domain;

import java.util.Objects;

/**
 * A raw record parsed from the Audit_Feed (auditd) stream, before normalization into a
 * {@link CommandEvent}. This is the post-execution detection/corroboration path: auditd cannot
 * block, it only detects (Req 2.1, 2.5).
 *
 * @param type        whether the record is an {@code EXECVE} or a {@code FILE_WRITE}
 * @param userId      the OS user identity the audited action ran as
 * @param commandText the reconstructed command line (for {@code EXECVE})
 * @param path        the affected file path (for {@code FILE_WRITE}), or {@code null}
 * @param cwd         the working directory, if available
 * @param timestamp   UTC epoch millis of the audited action
 */
public record RawAuditEvent(
        AuditType type,
        String userId,
        String commandText,
        String path,
        String cwd,
        long timestamp) {

    public RawAuditEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }

    /** The kind of audited action. */
    public enum AuditType {
        EXECVE,
        FILE_WRITE
    }
}
