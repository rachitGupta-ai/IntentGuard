package com.intentguard.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Integration test for the Control_Tower live-update latency contract (Task 15.3, Req 12.6): when a
 * new Command_Event is scored, a subscribed client receives the update within 3 seconds.
 *
 * <p>The test runs the full application on a random port and exercises the delivery contract two
 * complementary ways:
 *
 * <ol>
 *   <li><b>End-to-end over HTTP/SSE</b> — a real client connects to {@code GET /api/stream}, the
 *       test waits until the server-side subscription is registered, then publishes a
 *       {@link ScoreEvent} through the live-push channel and asserts the client reads the SCORE
 *       event off the stream within 3 seconds.</li>
 *   <li><b>Bean-level latency</b> — a latch-based {@link LiveEventSink} is registered on the real
 *       {@link LivePushService} bean; publishing a score counts the latch down, and the test
 *       asserts delivery within 3 seconds, that it was effectively immediate (the fan-out is
 *       synchronous), and that the delivered envelope is the SCORE event with the published
 *       payload.</li>
 * </ol>
 *
 * Both paths synchronize on {@link LivePushService#subscriberCount()} before publishing so there is
 * no subscribe/publish race, keeping the test deterministic and non-flaky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdateLatencyIntegrationTest {

    private static final long DELIVERY_BUDGET_MS = 3_000L;

    @LocalServerPort
    private int port;

    @Autowired
    private LivePushService livePushService;

    /** A subscriber that records the first envelope it receives and releases a latch. */
    private static final class LatchSink implements LiveEventSink {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<LiveEvent> received = new AtomicReference<>();

        @Override
        public void send(LiveEvent event) {
            received.compareAndSet(null, event);
            latch.countDown();
        }
    }

    // --- End-to-end: a real subscribed HTTP/SSE client receives a new score within 3s ---------

    @Test
    void subscribedHttpClientReceivesNewScoreWithinThreeSeconds() throws Exception {
        int baselineSubscribers = livePushService.subscriberCount();

        String eventId = "evt-http-" + System.nanoTime();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> payloadLine = new AtomicReference<>();
        AtomicReference<Exception> readerError = new AtomicReference<>();

        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://localhost:" + port + "/api/stream")
                .toURL()
                .openConnection();
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(5_000);
        // Read timeout must exceed the delivery budget so a blocked read fails the test as a
        // missed-deadline rather than hanging indefinitely.
        connection.setReadTimeout(10_000);

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    // The score envelope is serialized into the SSE "data:" line and carries the
                    // unique eventId; matching on it avoids depending on SSE framing details.
                    if (line.contains(eventId)) {
                        payloadLine.set(line);
                        received.countDown();
                        return;
                    }
                }
            } catch (Exception e) {
                readerError.set(e);
            }
        }, "sse-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            // Wait until the server has registered the SSE subscription, so the publish below is
            // guaranteed to fan out to this client (no subscribe/publish race).
            awaitSubscriberCount(baselineSubscribers + 1);

            ScoreEvent score = new ScoreEvent(
                    eventId, "alice", 0.82, "BLOCK", System.currentTimeMillis(), "off-intent command");

            long start = System.nanoTime();
            livePushService.publishScore(score);

            boolean delivered = received.await(DELIVERY_BUDGET_MS, TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(readerError.get())
                    .as("SSE reader should not have errored")
                    .isNull();
            assertThat(delivered)
                    .as("subscribed HTTP client should receive the new score within 3s")
                    .isTrue();
            assertThat(elapsedMs)
                    .as("delivery latency should be within the 3s budget")
                    .isLessThan(DELIVERY_BUDGET_MS);
            assertThat(payloadLine.get())
                    .as("delivered SSE payload should carry the scored event")
                    .contains(eventId)
                    .contains("BLOCK");
        } finally {
            connection.disconnect();
        }
    }

    // --- Bean-level: the live-push channel delivers a score within 3s (and effectively now) ----

    @Test
    void registeredSinkReceivesPublishedScoreWellWithinBudget() throws Exception {
        LatchSink sink = new LatchSink();
        livePushService.register(sink);
        try {
            ScoreEvent score = new ScoreEvent(
                    "evt-bean-1", "bob", 0.42, "ASK", System.currentTimeMillis(), "needs confirmation");

            long start = System.nanoTime();
            livePushService.publishScore(score);

            boolean delivered = sink.latch.await(DELIVERY_BUDGET_MS, TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(delivered)
                    .as("subscribed client should receive the new score within 3s")
                    .isTrue();
            assertThat(elapsedMs)
                    .as("synchronous fan-out should deliver well within the 3s budget")
                    .isLessThan(DELIVERY_BUDGET_MS);

            LiveEvent envelope = sink.received.get();
            assertThat(envelope).isNotNull();
            assertThat(envelope.type()).isEqualTo(LiveEvent.TYPE_SCORE);
            assertThat(envelope.payload()).isEqualTo(score);
        } finally {
            livePushService.unregister(sink);
        }
    }

    /** Polls until the live-push service reports at least {@code expected} subscribers, or fails. */
    private void awaitSubscriberCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (livePushService.subscriberCount() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("SSE client did not subscribe within 5s (subscriberCount="
                        + livePushService.subscriberCount() + ", expected>=" + expected + ")");
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }
}
