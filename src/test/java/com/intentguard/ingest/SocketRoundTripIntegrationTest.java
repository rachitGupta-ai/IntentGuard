package com.intentguard.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentguard.domain.Verdict;

/**
 * End-to-end integration test for the Shell_Hook blocking-gate socket round-trip (Task 2.4).
 *
 * <p>It binds a real {@link UnixDomainSocketServer} to a temporary Unix domain socket, connects a
 * client, sends a JSON {@link ShellHookRequest} line, and reads back the newline-terminated JSON
 * verdict. It asserts the three enforcement outcomes required by the walking-skeleton gate:
 *
 * <ul>
 *   <li>an {@code ALLOW} verdict lets the command proceed (Req 7.2);</li>
 *   <li>a {@code BLOCK} verdict prevents the command so the hook returns non-zero (Req 7.4);</li>
 *   <li>a missing/late verdict &mdash; a decision that overruns the decision budget, a decision
 *       error, or a malformed request &mdash; triggers the conservative fail-safe {@code BLOCK}
 *       (Req 5.8, 7.4).</li>
 * </ul>
 */
class SocketRoundTripIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<UnixDomainSocketServer> startedServers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UnixDomainSocketServer server : startedServers) {
            server.stop();
        }
        startedServers.clear();
    }

    @Test
    void allowVerdictLetsCommandProceed() throws Exception {
        UnixDomainSocketServer server =
                startServer(signal -> Verdict.allow("STUB_ALLOW"), 2000);

        JsonNode verdict = roundTrip(server, request("ls -la"));

        assertThat(verdict.get("action").asText()).isEqualTo("ALLOW");
    }

    @Test
    void blockVerdictPreventsCommandAndReturnsNonAllow() throws Exception {
        UnixDomainSocketServer server =
                startServer(
                        signal -> Verdict.block("STUB_BLOCK", "destructive command blocked"), 2000);

        JsonNode verdict = roundTrip(server, request("rm -rf /"));

        // A block verdict is anything other than ALLOW; the hook returns non-zero on it.
        assertThat(verdict.get("action").asText()).isEqualTo("BLOCK");
        assertThat(verdict.get("action").asText()).isNotEqualTo("ALLOW");
        assertThat(verdict.get("explanation").asText()).isNotEmpty();
    }

    @Test
    void lateVerdictExceedingBudgetTriggersFailSafeBlock() throws Exception {
        // Inject a small budget so the deadline fires quickly instead of waiting the full 2s.
        long budgetMs = 200;
        UnixDomainSocketServer server =
                startServer(
                        signal -> {
                            sleepUninterruptibly(5_000);
                            return Verdict.allow("SHOULD_NOT_BE_RETURNED");
                        },
                        budgetMs);

        long start = System.nanoTime();
        JsonNode verdict = roundTrip(server, request("sleep 5"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(verdict.get("action").asText()).isEqualTo("BLOCK");
        assertThat(verdict.get("reasonCode").asText())
                .isEqualTo(InteractiveSignalIngestor.REASON_BUDGET_EXCEEDED);
        // The fail-safe must fire close to the budget, well before the provider's 5s sleep.
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    void decisionErrorTriggersFailSafeBlock() throws Exception {
        UnixDomainSocketServer server =
                startServer(
                        signal -> {
                            throw new IllegalStateException("scoring pipeline unavailable");
                        },
                        2000);

        JsonNode verdict = roundTrip(server, request("whoami"));

        assertThat(verdict.get("action").asText()).isEqualTo("BLOCK");
        assertThat(verdict.get("reasonCode").asText())
                .isEqualTo(InteractiveSignalIngestor.REASON_DECISION_ERROR);
    }

    @Test
    void malformedRequestTriggersFailSafeBlock() throws Exception {
        // The provider would allow anything, proving the block comes from the codec fail-safe.
        UnixDomainSocketServer server =
                startServer(signal -> Verdict.allow("STUB_ALLOW"), 2000);

        JsonNode verdict = roundTrip(server, "this is not valid json");

        assertThat(verdict.get("action").asText()).isEqualTo("BLOCK");
        assertThat(verdict.get("reasonCode").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Build and start a {@link UnixDomainSocketServer} wired to the given decision provider and
     * decision budget, bound to a unique temporary socket path.
     */
    private UnixDomainSocketServer startServer(
            InteractiveDecisionProvider provider, long budgetMs) throws IOException {
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(provider), budgetMs);
        ShellSignalCodec codec = new ShellSignalCodec(MAPPER);
        String socketPath = uniqueSocketPath();
        UnixDomainSocketServer server = new UnixDomainSocketServer(ingestor, codec, socketPath);
        server.start();
        assertThat(server.isRunning())
                .as("socket server should bind in the test environment")
                .isTrue();
        startedServers.add(server);
        return server;
    }

    /** Connect to the server's socket, send one request line, and read back the verdict JSON. */
    private JsonNode roundTrip(UnixDomainSocketServer server, String requestJson) throws Exception {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(server.socketPath());
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            try (OutputStream out = Channels.newOutputStream(channel);
                    InputStream in = Channels.newInputStream(channel)) {
                out.write((requestJson + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String response = readLine(in);
                assertThat(response).as("server must return a verdict line").isNotBlank();
                return MAPPER.readTree(response);
            }
        }
    }

    private static String request(String command) {
        return "{\"userId\":\"alice\",\"commandText\":\""
                + command
                + "\",\"cwd\":\"/home/alice\",\"inputOrigin\":\"TYPED\"}";
    }

    /** Read a single newline-terminated line (or until end-of-stream) from the socket. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static String uniqueSocketPath() {
        // Keep the path short: Unix domain socket paths are capped (~104 bytes on macOS).
        return Path.of("/tmp", "igt-" + System.nanoTime() + ".sock").toString();
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
