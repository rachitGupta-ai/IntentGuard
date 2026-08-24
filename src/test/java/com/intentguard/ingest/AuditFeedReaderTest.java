package com.intentguard.ingest;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.SignalSource;

/**
 * Unit tests for {@link AuditFeedReader}: userspace parsing of sample auditd lines into
 * {@link CommandEvent}s via an injected line source, with no real auditd and no filesystem
 * dependency (Req 2.1, 2.5).
 */
class AuditFeedReaderTest {

    private AuditFeedReader newReader() {
        // No configured audit log path: tailing is disabled, but ingestLine/processLines work.
        return new AuditFeedReader(new AuditLineParser(), new AuditSignalNormalizer(() -> "eid"), "");
    }

    @Test
    void ingestsExecveLineIntoAuditSourcedCommandEvent() {
        AuditFeedReader reader = newReader();
        List<CommandEvent> delivered = new ArrayList<>();
        reader.setSink(delivered::add);

        Optional<CommandEvent> event =
                reader.ingestLine(
                        "type=EXECVE msg=audit(1710000000.123:1): a0=\"ls\" a1=\"-la\" auid=1000 uid=1000 "
                                + "cwd=\"/home/alice\"");

        assertThat(event).isPresent();
        assertThat(event.get().signalSource()).isEqualTo(SignalSource.AUDIT);
        assertThat(event.get().commandText()).isEqualTo("ls -la");
        assertThat(event.get().timestamp()).isEqualTo(1_710_000_000_123L);
        assertThat(delivered).hasSize(1);
    }

    @Test
    void skipsIrrelevantLinesWithoutDelivering() {
        AuditFeedReader reader = newReader();
        List<CommandEvent> delivered = new ArrayList<>();
        reader.setSink(delivered::add);

        assertThat(reader.ingestLine("type=CWD msg=audit(1710000000.000:1): cwd=\"/home\"")).isEmpty();
        assertThat(reader.ingestLine("")).isEmpty();
        assertThat(delivered).isEmpty();
    }

    @Test
    void processesAStreamOfMixedAuditLines() throws Exception {
        AuditFeedReader reader = newReader();
        List<CommandEvent> delivered = new ArrayList<>();
        reader.setSink(delivered::add);

        String stream =
                "type=EXECVE msg=audit(1710000000.100:1): a0=\"git\" a1=\"push\" auid=1000 uid=1000\n"
                        + "type=CWD msg=audit(1710000000.100:1): cwd=\"/home/alice\"\n"
                        + "type=PATH msg=audit(1710000000.200:2): name=\"/etc/hosts\" auid=1000 uid=1000\n";

        reader.processLines(new BufferedReader(new StringReader(stream)));

        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0).commandText()).isEqualTo("git push");
        assertThat(delivered.get(1).commandText()).isEqualTo("/etc/hosts");
        assertThat(delivered).allMatch(e -> e.signalSource() == SignalSource.AUDIT);
    }

    @Test
    void tailingDisabledWhenNoPathConfigured() {
        AuditFeedReader reader = newReader();

        reader.start();

        assertThat(reader.isRunning()).isFalse();
        assertThat(reader.auditLogPath()).isNull();
        reader.stop(); // must be safe even when never started
    }

    @Test
    void tailingDisabledWhenConfiguredPathIsMissing() {
        AuditFeedReader reader =
                new AuditFeedReader(
                        new AuditLineParser(),
                        new AuditSignalNormalizer(() -> "eid"),
                        "/nonexistent/path/to/audit.log");

        reader.start();

        assertThat(reader.isRunning()).isFalse();
    }
}
