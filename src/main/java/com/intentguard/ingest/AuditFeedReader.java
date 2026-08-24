package com.intentguard.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.RawAuditEvent;

/**
 * The Audit_Feed reader: tails the auditd log/stream from userspace and turns execve / file-write
 * records into {@link CommandEvent}s (Req 2.1, 2.5).
 *
 * <p>This is the post-execution detection path. It runs <strong>entirely in userspace</strong> —
 * it reads text lines from a log file (or any injected line source) and never loads a kernel
 * module (Req 2.5). Each received line is parsed by {@link AuditLineParser} and normalized by
 * {@link AuditSignalNormalizer} promptly and synchronously, with no batching or buffering delay,
 * so a received event becomes a {@code CommandEvent} well within the 500 ms target (Req 2.1).
 *
 * <p>Bound as a {@link SmartLifecycle} that starts a background tail thread only when a real
 * audit log path is configured and exists. In any environment where the path is unset or absent
 * (tests, developer machines, CI) it degrades to a logged notice rather than failing startup —
 * mirroring the graceful degradation of {@link UnixDomainSocketServer}. The parsing / normalization
 * logic is exposed through {@link #ingestLine(String)} and {@link #processLines(BufferedReader)} so
 * it is fully testable without a running auditd.
 */
@Component
public class AuditFeedReader implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuditFeedReader.class);

    private final AuditLineParser parser;
    private final AuditSignalNormalizer normalizer;
    private final Path auditLogPath;

    /**
     * Where normalized audit Command_Events are delivered. Defaults to a debug log so the reader
     * is safe to run standalone; the full pipeline wiring (Task 13.1) installs a real sink via
     * {@link #setSink(Consumer)}.
     */
    private volatile Consumer<CommandEvent> sink =
            event -> log.debug("Audit_Feed event (no sink installed): {}", event.commandText());

    private volatile boolean running;
    private Thread tailThread;

    public AuditFeedReader(
            AuditLineParser parser,
            AuditSignalNormalizer normalizer,
            @Value("${intentguard.audit.log-path:}") String auditLogPath) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.auditLogPath =
                (auditLogPath == null || auditLogPath.isBlank()) ? null : Path.of(auditLogPath);
    }

    /**
     * Install the consumer that receives normalized Audit_Feed Command_Events. Delivery is
     * synchronous on the tailing thread.
     */
    public void setSink(Consumer<CommandEvent> sink) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    /**
     * Parse and normalize a single auditd line, delivering the resulting {@link CommandEvent} to
     * the sink when the line is a relevant execve / file-write record.
     *
     * @param line a raw auditd log line
     * @return the produced Command_Event, or empty if the line was not a relevant record
     */
    public Optional<CommandEvent> ingestLine(String line) {
        Optional<RawAuditEvent> parsed;
        try {
            parsed = parser.parse(line);
        } catch (RuntimeException e) {
            // A single malformed line must never stall the feed.
            log.debug("Skipping unparseable audit line: {}", e.getMessage());
            return Optional.empty();
        }
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        CommandEvent event = normalizer.normalize(parsed.get());
        sink.accept(event);
        return Optional.of(event);
    }

    /**
     * Process every line currently available from the given reader (used by the tail loop and by
     * tests feeding a sample auditd stream).
     *
     * @param reader a source of auditd log lines
     * @throws IOException if reading fails
     */
    public void processLines(BufferedReader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        String line;
        while ((line = reader.readLine()) != null) {
            ingestLine(line);
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (auditLogPath == null) {
            log.info(
                    "Audit_Feed reader disabled: no audit log path configured "
                            + "(set intentguard.audit.log-path to enable userspace auditd tailing).");
            return;
        }
        if (!Files.isReadable(auditLogPath)) {
            log.warn(
                    "Audit_Feed reader disabled: configured audit log path {} is not readable in "
                            + "this environment. Post-execution detection is unavailable.",
                    auditLogPath);
            return;
        }
        running = true;
        tailThread = new Thread(this::tailLoop, "intentguard-audit-tail");
        tailThread.setDaemon(true);
        tailThread.start();
        log.info("Audit_Feed reader tailing auditd log at {}", auditLogPath);
    }

    private void tailLoop() {
        try (InputStream in = Files.newInputStream(auditLogPath);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    // Reached end of file; wait briefly for appended lines (tail -f semantics).
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                ingestLine(line);
            }
        } catch (IOException e) {
            log.warn("Audit_Feed tail loop stopped: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (tailThread != null) {
            tailThread.interrupt();
        }
        log.info("Audit_Feed reader stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The configured audit log path, or {@code null} when tailing is disabled. */
    public Path auditLogPath() {
        return auditLogPath;
    }
}
