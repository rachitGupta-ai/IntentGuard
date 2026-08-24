package com.intentguard.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.InputOrigin;
import com.intentguard.domain.IntentSource;
import com.intentguard.domain.RawAuditEvent;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for {@link AuditSignalNormalizer}: field-preserving normalization of a raw
 * Audit_Feed record into a {@link CommandEvent} with {@code signalSource=AUDIT} (Req 2.1, 2.5).
 */
class AuditSignalNormalizerTest {

    private final AuditSignalNormalizer normalizer =
            new AuditSignalNormalizer(() -> "fixed-audit-id");

    @Test
    void normalizesExecvePreservingFields() {
        RawAuditEvent raw =
                new RawAuditEvent(
                        RawAuditEvent.AuditType.EXECVE,
                        "1000",
                        "git commit -m fix",
                        null,
                        "/home/alice/project",
                        1_710_000_000_123L);

        CommandEvent event = normalizer.normalize(raw);

        assertThat(event.eventId()).isEqualTo("fixed-audit-id");
        assertThat(event.commandText()).isEqualTo("git commit -m fix");
        assertThat(event.cwd()).isEqualTo("/home/alice/project");
        assertThat(event.timestamp()).isEqualTo(1_710_000_000_123L);
        assertThat(event.userId()).isEqualTo("1000");
        assertThat(event.actorType()).isEqualTo(ActorType.HUMAN);
        assertThat(event.signalSource()).isEqualTo(SignalSource.AUDIT);
        assertThat(event.inputOrigin()).isEqualTo(InputOrigin.UNKNOWN);
        assertThat(event.intentSource()).isEqualTo(IntentSource.NONE);
        assertThat(event.envContext()).containsEntry("auditType", "EXECVE");
    }

    @Test
    void normalizesFileWriteUsingPathAsObservedAction() {
        RawAuditEvent raw =
                new RawAuditEvent(
                        RawAuditEvent.AuditType.FILE_WRITE,
                        "0",
                        null,
                        "/etc/passwd",
                        "/root",
                        1_710_000_001_500L);

        CommandEvent event = normalizer.normalize(raw);

        assertThat(event.commandText()).isEqualTo("/etc/passwd");
        assertThat(event.cwd()).isEqualTo("/root");
        assertThat(event.userId()).isEqualTo("0");
        assertThat(event.signalSource()).isEqualTo(SignalSource.AUDIT);
        assertThat(event.envContext()).containsEntry("auditType", "FILE_WRITE");
        assertThat(event.envContext()).containsEntry("auditPath", "/etc/passwd");
    }

    @Test
    void generatesUniqueEventIdsWithDefaultConstructor() {
        AuditSignalNormalizer uuidNormalizer = new AuditSignalNormalizer();
        RawAuditEvent raw =
                new RawAuditEvent(RawAuditEvent.AuditType.EXECVE, "1000", "ls", null, "/", 1L);

        CommandEvent first = uuidNormalizer.normalize(raw);
        CommandEvent second = uuidNormalizer.normalize(raw);

        assertThat(first.eventId()).isNotNull().isNotEqualTo(second.eventId());
    }
}
