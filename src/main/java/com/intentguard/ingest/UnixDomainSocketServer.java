package com.intentguard.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

/**
 * The Shell_Hook listener: a service-account-owned Unix domain socket that accepts synchronous
 * blocking-gate requests (Req 2.2).
 *
 * <p>For each connection it reads a single JSON {@link ShellHookRequest} line, decodes it into a
 * {@link RawShellSignal}, hands it to the {@link SignalIngestor} (which enforces the 2-second
 * decision budget, Req 5.8), and writes the {@link Verdict} back before closing the connection.
 *
 * <p>Bound as a {@link SmartLifecycle} so a bind failure (e.g. insufficient permissions on the
 * configured socket directory in a non-deployment environment) degrades to a logged warning
 * rather than preventing the engine from starting.
 */
@Component
public class UnixDomainSocketServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UnixDomainSocketServer.class);
    private static final int MAX_REQUEST_BYTES = 1 << 20; // 1 MiB safety cap per request

    private final SignalIngestor ingestor;
    private final ShellSignalCodec codec;
    private final Path socketPath;

    private volatile boolean running;
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;
    private ExecutorService connectionExecutor;

    public UnixDomainSocketServer(
            SignalIngestor ingestor,
            ShellSignalCodec codec,
            @Value("${intentguard.socket.path:/var/run/intentguard/intentguard.sock}")
                    String socketPath) {
        this.ingestor = ingestor;
        this.codec = codec;
        this.socketPath = Path.of(socketPath);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            prepareSocketFile();
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(address);

            AtomicLong counter = new AtomicLong();
            connectionExecutor =
                    Executors.newCachedThreadPool(
                            runnable -> {
                                Thread thread = new Thread(runnable);
                                thread.setName("intentguard-hook-conn-" + counter.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });

            running = true;
            acceptThread = new Thread(this::acceptLoop, "intentguard-hook-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("Shell_Hook Unix domain socket listening at {}", socketPath);
        } catch (IOException e) {
            handleBindFailure(e);
        } catch (UnsupportedOperationException e) {
            handleBindFailure(e);
        }
    }

    private void handleBindFailure(Exception e) {
            // Fail-open on startup only: the engine still runs and can serve verdicts to any
            // in-process callers; the socket is simply unavailable until the environment permits.
            running = false;
            log.warn(
                    "Could not bind Shell_Hook socket at {}: {}. The blocking gate socket is "
                            + "unavailable in this environment.",
                    socketPath,
                    e.getMessage());
    }

    private void prepareSocketFile() throws IOException {
        Path parent = socketPath.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            // Guarded: createDirectories() throws FileAlreadyExistsException when the target is a
            // symlink to an existing directory (e.g. /tmp -> /private/tmp on macOS).
            Files.createDirectories(parent);
        }
        // A stale socket file from a prior run would make bind() fail with "address in use".
        Files.deleteIfExists(socketPath);
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel channel = serverChannel.accept();
                connectionExecutor.submit(() -> handleConnection(channel));
            } catch (IOException e) {
                if (running) {
                    log.debug("Accept loop error (continuing): {}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(SocketChannel channel) {
        try (SocketChannel c = channel;
                InputStream in = Channels.newInputStream(c);
                OutputStream out = Channels.newOutputStream(c)) {
            String requestJson = readRequest(in);
            Verdict verdict = decideOrFailSafe(requestJson);
            out.write(codec.encodeVerdict(verdict).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.debug("Connection handling error: {}", e.getMessage());
        }
    }

    private Verdict decideOrFailSafe(String requestJson) {
        try {
            RawShellSignal signal = codec.decodeRequest(requestJson);
            return ingestor.submitInteractive(signal);
        } catch (Exception e) {
            // A malformed request cannot be scored; fail safe by blocking rather than allowing.
            log.warn("Rejecting malformed Shell_Hook request: {}", e.getMessage());
            return Verdict.block(
                    "MALFORMED_REQUEST",
                    "IntentGuard could not parse the command request and blocked it as a "
                            + "precaution.");
        }
    }

    /** Read a single request, terminated by a newline or end-of-stream, up to the size cap. */
    private static String readRequest(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        int count = 0;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            sb.append((char) b);
            if (++count > MAX_REQUEST_BYTES) {
                throw new IOException("Shell_Hook request exceeded size cap");
            }
        }
        return sb.toString();
    }

    @Override
    public synchronized void stop() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            log.debug("Error closing server channel: {}", e.getMessage());
        }
        if (connectionExecutor != null) {
            connectionExecutor.shutdownNow();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            log.debug("Error deleting socket file: {}", e.getMessage());
        }
        log.info("Shell_Hook Unix domain socket stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The filesystem path the listener binds to. */
    public Path socketPath() {
        return socketPath;
    }
}
