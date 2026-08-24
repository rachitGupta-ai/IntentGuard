package com.intentguard.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live-update surface of the Control_Tower API (design "REST and WebSocket API"; Req 12.6).
 *
 * <p>Exposes the Server-Sent Events fallback of the WebSocket/SSE live channel:
 * {@code GET /api/stream} (producing {@code text/event-stream}) subscribes the calling
 * Control_Tower client to the shared {@link LivePushService}. From then on the client receives
 * every live session, score, and alert event pushed by the engine — each as a named SSE event
 * ({@code SESSION} / {@code SCORE} / {@code ALERT}) carrying the {@link LiveEvent} envelope — with
 * end-to-end latency well within the 3-second budget.
 *
 * <p><strong>SECURITY — UNAUTHENTICATED STREAM.</strong> Like the rest of the Control_Tower API
 * (see {@link ControlTowerController}), this endpoint currently has <em>no authentication</em>. It
 * exposes live decision/score/alert telemetry and MUST be bound to a loopback/OS-restricted
 * interface owned by the {@code intentguard} service account and/or placed behind an authenticating
 * filter before any non-prototype use.
 */
@RestController
@RequestMapping("/api")
public class LivePushController {

    private final LivePushService livePushService;

    public LivePushController(LivePushService livePushService) {
        this.livePushService = livePushService;
    }

    /**
     * Subscribes the caller to the live event channel and returns the {@link SseEmitter} Spring MVC
     * keeps open for the streamed session/score/alert events (Req 12.6).
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return livePushService.subscribe();
    }
}
