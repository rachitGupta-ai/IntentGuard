package com.intentguard.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.intentguard.profile.SessionAnomalyAlert;
import com.intentguard.watchdog.MonitoringGapAlert;

/**
 * Unit tests for {@link LivePushService} — the Control_Tower live-push fan-out (Req 12.6).
 *
 * <p>Uses an in-memory {@link RecordingSink} so the fan-out is exercised without a live HTTP/SSE
 * connection. Cover: a published score/session/alert event reaches every registered subscriber
 * with the correct envelope type and payload; the two engine alert types are projected onto the
 * live channel; an unregistered sink stops receiving; a sink that fails on delivery is dropped; and
 * {@link LivePushService#subscribe()} registers an {@link SseEmitter}-backed subscriber.
 */
class LivePushServiceTest {

    private static final long NOW = 1_700_000_000_000L;

    private LivePushService service;

    @BeforeEach
    void setUp() {
        service = new LivePushService();
    }

    /** A fake subscriber that records every envelope it receives. */
    private static final class RecordingSink implements LiveEventSink {
        final List<LiveEvent> received = new ArrayList<>();

        @Override
        public void send(LiveEvent event) {
            received.add(event);
        }
    }

    /** A subscriber that always fails delivery (simulating a client that has gone away). */
    private static final class FailingSink implements LiveEventSink {
        @Override
        public void send(LiveEvent event) throws IOException {
            throw new IOException("client gone");
        }
    }

    // --- A published score event is fanned out to every subscriber (Req 12.6) -----------------

    @Test
    void publishScoreFansOutToAllRegisteredSinks() {
        RecordingSink a = new RecordingSink();
        RecordingSink b = new RecordingSink();
        service.register(a);
        service.register(b);

        ScoreEvent score = new ScoreEvent("evt-1", "alice", 0.82, "BLOCK", NOW, "off-intent command");
        service.publishScore(score);

        assertThat(a.received).hasSize(1);
        assertThat(b.received).hasSize(1);
        LiveEvent envelope = a.received.get(0);
        assertThat(envelope.type()).isEqualTo(LiveEvent.TYPE_SCORE);
        assertThat(envelope.timestamp()).isEqualTo(NOW);
        assertThat(envelope.payload()).isEqualTo(score);
    }

    // --- A published session update is delivered as a SESSION envelope ------------------------

    @Test
    void publishSessionUpdateDeliversSessionEnvelope() {
        RecordingSink sink = new RecordingSink();
        service.register(sink);

        SessionUpdateEvent session =
                new SessionUpdateEvent("sess-1", "alice", "deploy the release", "OPENED", NOW);
        service.publishSessionUpdate(session);

        assertThat(sink.received).hasSize(1);
        LiveEvent envelope = sink.received.get(0);
        assertThat(envelope.type()).isEqualTo(LiveEvent.TYPE_SESSION);
        assertThat(envelope.payload()).isEqualTo(session);
    }

    // --- The two engine alert types are projected onto the live channel -----------------------

    @Test
    void publishSessionAnomalyAlertProjectsEvidenceOntoLiveChannel() {
        RecordingSink sink = new RecordingSink();
        service.register(sink);

        SessionAnomalyAlert alert = new SessionAnomalyAlert(
                "alice", NOW, 0.85, 0.6, List.of(0.8, 0.85, 0.9), "sustained deviation for alice");
        service.publishAlert(alert);

        assertThat(sink.received).hasSize(1);
        LiveEvent envelope = sink.received.get(0);
        assertThat(envelope.type()).isEqualTo(LiveEvent.TYPE_ALERT);
        assertThat(envelope.payload()).isInstanceOf(AlertEvent.class);
        AlertEvent payload = (AlertEvent) envelope.payload();
        assertThat(payload.alertType()).isEqualTo(AlertEvent.TYPE_SESSION_ANOMALY);
        assertThat(payload.userId()).isEqualTo("alice");
        assertThat(payload.highRisk()).isTrue();
        assertThat(payload.evidenceDeviations()).containsExactly(0.8, 0.85, 0.9);
    }

    @Test
    void publishMonitoringGapAlertProjectsGapOntoLiveChannel() {
        RecordingSink sink = new RecordingSink();
        service.register(sink);

        MonitoringGapAlert alert =
                new MonitoringGapAlert(NOW, NOW - 6000, 6000, 5000, "no audit events for 6s");
        service.publishAlert(alert);

        assertThat(sink.received).hasSize(1);
        AlertEvent payload = (AlertEvent) sink.received.get(0).payload();
        assertThat(payload.alertType()).isEqualTo(AlertEvent.TYPE_MONITORING_GAP);
        assertThat(payload.userId()).isNull();
        assertThat(payload.highRisk()).isTrue();
    }

    // --- Deregistration and dead-connection cleanup -------------------------------------------

    @Test
    void unregisteredSinkNoLongerReceivesEvents() {
        RecordingSink sink = new RecordingSink();
        service.register(sink);
        service.unregister(sink);

        service.publishScore(new ScoreEvent("evt-2", "bob", 0.1, "ALLOW", NOW, null));

        assertThat(sink.received).isEmpty();
        assertThat(service.subscriberCount()).isZero();
    }

    @Test
    void sinkThatFailsDeliveryIsDroppedButOthersStillReceive() {
        RecordingSink healthy = new RecordingSink();
        FailingSink broken = new FailingSink();
        service.register(broken);
        service.register(healthy);

        service.publishScore(new ScoreEvent("evt-3", "carol", 0.5, "ASK", NOW, "needs confirmation"));

        // Healthy subscriber still received the event; the broken one was removed.
        assertThat(healthy.received).hasSize(1);
        assertThat(service.subscriberCount()).isEqualTo(1);
    }

    // --- subscribe() registers an SseEmitter-backed subscriber --------------------------------

    @Test
    void subscribeRegistersAnEmitterBackedSubscriber() {
        SseEmitter emitter = service.subscribe();

        assertThat(emitter).isNotNull();
        assertThat(service.subscriberCount()).isEqualTo(1);
    }
}
