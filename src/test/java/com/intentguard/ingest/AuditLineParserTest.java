package com.intentguard.ingest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.RawAuditEvent;

/**
 * Unit tests for {@link AuditLineParser}: userspace parsing of sample auditd execve / file-write
 * log lines into {@link RawAuditEvent} records, preserving user identity, timestamp, command/exe,
 * cwd, and file path (Req 2.1, 2.5). No real auditd is involved — sample lines are fed directly.
 */
class AuditLineParserTest {

    private final AuditLineParser parser = new AuditLineParser();

    @Test
    void parsesExecveRecordWithArgumentVector() {
        String line =
                "type=EXECVE msg=audit(1710000000.123:456): argc=3 a0=\"git\" a1=\"commit\" a2=\"-m\" "
                        + "auid=1000 uid=1000 cwd=\"/home/alice/project\"";

        Optional<RawAuditEvent> parsed = parser.parse(line);

        assertThat(parsed).isPresent();
        RawAuditEvent event = parsed.get();
        assertThat(event.type()).isEqualTo(RawAuditEvent.AuditType.EXECVE);
        assertThat(event.userId()).isEqualTo("1000");
        assertThat(event.commandText()).isEqualTo("git commit -m");
        assertThat(event.cwd()).isEqualTo("/home/alice/project");
        // 1710000000.123 seconds -> epoch millis.
        assertThat(event.timestamp()).isEqualTo(1_710_000_000_123L);
        assertThat(event.path()).isNull();
    }

    @Test
    void parsesFileWriteRecordFromPathType() {
        String line =
                "type=PATH msg=audit(1710000001.500:789): item=0 name=\"/etc/passwd\" "
                        + "nametype=NORMAL auid=1000 uid=1000 cwd=\"/root\"";

        Optional<RawAuditEvent> parsed = parser.parse(line);

        assertThat(parsed).isPresent();
        RawAuditEvent event = parsed.get();
        assertThat(event.type()).isEqualTo(RawAuditEvent.AuditType.FILE_WRITE);
        assertThat(event.userId()).isEqualTo("1000");
        assertThat(event.path()).isEqualTo("/etc/passwd");
        assertThat(event.cwd()).isEqualTo("/root");
        assertThat(event.timestamp()).isEqualTo(1_710_000_001_500L);
        assertThat(event.commandText()).isNull();
    }

    @Test
    void prefersAuidOverUidForIdentity() {
        String line = "type=EXECVE msg=audit(1710000000.000:1): a0=\"ls\" auid=1000 uid=0";

        RawAuditEvent event = parser.parse(line).orElseThrow();

        assertThat(event.userId()).isEqualTo("1000");
    }

    @Test
    void fallsBackToUidWhenAuidUnset() {
        String line = "type=EXECVE msg=audit(1710000000.000:1): a0=\"ls\" auid=4294967295 uid=33";

        RawAuditEvent event = parser.parse(line).orElseThrow();

        assertThat(event.userId()).isEqualTo("33");
    }

    @Test
    void classifiesExecveFromSyscallRecord() {
        String line =
                "type=SYSCALL msg=audit(1710000000.000:1): syscall=execve success=yes exe=\"/usr/bin/bash\" "
                        + "auid=1000 uid=1000";

        RawAuditEvent event = parser.parse(line).orElseThrow();

        assertThat(event.type()).isEqualTo(RawAuditEvent.AuditType.EXECVE);
        // No a0.. argument vector on the SYSCALL line, so fall back to exe.
        assertThat(event.commandText()).isEqualTo("/usr/bin/bash");
    }

    @Test
    void ignoresIrrelevantRecordTypes() {
        assertThat(parser.parse("type=CWD msg=audit(1710000000.000:1): cwd=\"/home\"")).isEmpty();
        assertThat(parser.parse("type=PROCTITLE msg=audit(1710000000.000:1): proctitle=6C73")).isEmpty();
    }

    @Test
    void returnsEmptyForNullBlankOrGarbage() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("not an audit line at all")).isEmpty();
    }

    @Test
    void pathRecordWithoutNameIsNotActionable() {
        String line = "type=PATH msg=audit(1710000000.000:1): item=0 nametype=PARENT auid=1000";

        assertThat(parser.parse(line)).isEmpty();
    }

    @Test
    void handlesMissingCwdGracefully() {
        String line = "type=EXECVE msg=audit(1710000000.000:1): a0=\"whoami\" auid=1000 uid=1000";

        RawAuditEvent event = parser.parse(line).orElseThrow();

        assertThat(event.cwd()).isNull();
        assertThat(event.commandText()).isEqualTo("whoami");
    }
}
