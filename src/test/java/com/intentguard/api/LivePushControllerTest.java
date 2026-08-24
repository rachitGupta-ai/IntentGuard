package com.intentguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit test for {@link LivePushController}: {@code GET /api/stream} subscribes the caller to the
 * shared {@link LivePushService} and returns the live {@link SseEmitter} (Req 12.6).
 */
class LivePushControllerTest {

    @Test
    void streamSubscribesAndReturnsAnEmitter() {
        LivePushService service = new LivePushService();
        LivePushController controller = new LivePushController(service);

        SseEmitter emitter = controller.stream();

        assertThat(emitter).isNotNull();
        assertThat(service.subscriberCount()).isEqualTo(1);
    }
}
