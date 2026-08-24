package com.intentguard.api;

import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A {@link LiveEventSink} backed by a Spring {@link SseEmitter}, i.e. one subscribed Control_Tower
 * browser connected over Server-Sent Events (the SSE fallback of the WebSocket/SSE live channel,
 * Req 12.6). Each published {@link LiveEvent} is written as a named SSE event whose {@code name} is
 * the envelope's {@code type} and whose {@code data} is the JSON-serialized envelope.
 */
public final class SseEmitterSink implements LiveEventSink {

    private final SseEmitter emitter;

    public SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    @Override
    public void send(LiveEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(event.type()).data(event));
    }
}
