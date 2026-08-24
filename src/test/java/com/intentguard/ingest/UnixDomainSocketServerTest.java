package com.intentguard.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentguard.domain.Verdict;

/**
 * Round-trip test for the Shell_Hook Unix domain socket listener: a client writes a JSON request
 * and reads back the JSON verdict produced by the ingestor over the socket (Req 2.2).
 */
class UnixDomainSocketServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Path socketPath;
    private UnixDomainSocketServer server;

    @BeforeEach
    void setUp() {
        // Short absolute path to stay well under the OS Unix-socket path length limit.
        socketPath = Path.of("/tmp", "ig-sock-test-" + System.nanoTime() + ".sock");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop();
        }
        Files.deleteIfExists(socketPath);
    }

    private void startServer(InteractiveDecisionProvider provider) {
        InteractiveSignalIngestor ingestor =
                new InteractiveSignalIngestor(TestObjectProvider.of(provider), 2000);
        ShellSignalCodec codec = new ShellSignalCodec(mapper);
        server = new UnixDomainSocketServer(ingestor, codec, socketPath.toString());
        server.start();
        assertThat(server.isRunning()).isTrue();
    }

    private Map<String, Object> roundTrip(String requestJson) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            try (OutputStream out = Channels.newOutputStream(channel)) {
                out.write((requestJson + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                try (InputStream in = Channels.newInputStream(channel)) {
                    String response = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(response, Map.class);
                    return parsed;
                }
            }
        }
    }

    @Test
    void allowVerdictIsReturnedOverTheSocket() throws IOException {
        startServer(s -> Verdict.allow("STUB_ALLOW"));

        Map<String, Object> verdict =
                roundTrip("{\"userId\":\"alice\",\"commandText\":\"ls\",\"cwd\":\"/home/alice\"}");

        assertThat(verdict.get("action")).isEqualTo("ALLOW");
        assertThat(verdict.get("reasonCode")).isEqualTo("STUB_ALLOW");
    }

    @Test
    void blockVerdictIsReturnedOverTheSocket() throws IOException {
        startServer(
                s ->
                        s.commandText().contains("rm")
                                ? Verdict.block("STUB_BLOCK", "dangerous")
                                : Verdict.allow("STUB_ALLOW"));

        Map<String, Object> verdict =
                roundTrip(
                        "{\"userId\":\"alice\",\"commandText\":\"rm -rf /\",\"cwd\":\"/home/alice\"}");

        assertThat(verdict.get("action")).isEqualTo("BLOCK");
        assertThat(verdict.get("reasonCode")).isEqualTo("STUB_BLOCK");
        assertThat(verdict.get("explanation")).isEqualTo("dangerous");
    }

    @Test
    void malformedRequestFailsSafeWithBlock() throws IOException {
        startServer(s -> Verdict.allow("STUB_ALLOW"));

        Map<String, Object> verdict = roundTrip("this is not json");

        assertThat(verdict.get("action")).isEqualTo("BLOCK");
        assertThat(verdict.get("reasonCode")).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void requestWithoutInputOriginIsAcceptedAndScored() throws IOException {
        startServer(
                s -> {
                    // A missing typed-vs-pasted indicator arrives as a null inputOrigin on the
                    // raw signal and must not break decoding (recorded as UNKNOWN downstream).
                    assertThat(s.inputOrigin()).isNull();
                    return Verdict.allow("STUB_ALLOW");
                });

        Map<String, Object> verdict =
                roundTrip("{\"userId\":\"bob\",\"commandText\":\"pwd\",\"cwd\":\"/\"}");

        assertThat(verdict.get("action")).isEqualTo("ALLOW");
    }
}
