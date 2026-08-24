package com.intentguard.api;

import java.io.IOException;

/**
 * A single subscribed Control_Tower client's receiving end of the live channel (Req 12.6). The
 * {@link LivePushService} fans every published {@link LiveEvent} out to all registered sinks.
 *
 * <p>Abstracting the transport behind this interface keeps {@link LivePushService} testable without
 * a live HTTP connection: production uses an {@code SseEmitter}-backed sink, while unit tests
 * register an in-memory fake and assert what it received.
 */
public interface LiveEventSink {

    /**
     * Delivers one event to this client. Implementations should push the event immediately so the
     * end-to-end latency stays well within the 3-second budget (Req 12.6).
     *
     * @throws IOException if the client connection has failed; the service then deregisters the sink
     */
    void send(LiveEvent event) throws IOException;
}
