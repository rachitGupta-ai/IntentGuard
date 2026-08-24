package com.intentguard.api;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.intentguard.profile.SessionAnomalyAlert;
import com.intentguard.translation.LanguagePreferenceService;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.TranslationService;
import com.intentguard.watchdog.MonitoringGapAlert;

/**
 * The live-push fan-out for the Control_Tower live channel (design "REST and WebSocket API";
 * Req 12.6). It maintains the set of subscribed clients ({@link LiveEventSink}s) and pushes every
 * published session / score / alert event to all of them <em>synchronously and immediately</em>, so
 * a subscribed client receives an event well within the 3-second delivery budget.
 *
 * <p>The concrete transport is the SSE fallback of the WebSocket/SSE channel: {@link #subscribe()}
 * hands the caller an {@link SseEmitter} and registers an {@link SseEmitterSink} for it. Sinks are
 * abstracted behind {@link LiveEventSink} so the fan-out is unit-testable without a live HTTP
 * connection — a test can {@link #register(LiveEventSink)} an in-memory fake and assert what it
 * received. A sink whose {@code send} throws (client gone) is dropped from the registry.
 */
@Service
public class LivePushService {

    private static final Logger log = LoggerFactory.getLogger(LivePushService.class);

    /** Long-lived SSE connections should not time out on their own; the client manages reconnects. */
    static final long SSE_TIMEOUT_MILLIS = 0L;

    private final CopyOnWriteArrayList<LiveEventSink> sinks = new CopyOnWriteArrayList<>();

    /**
     * Optional per-operator outbound-translation collaborators (Req 2.1, 2.6). When both are present
     * (Spring-supplied), {@link #subscribe(String)} wraps each subscriber's transport in a
     * {@link TranslatingLiveEventSink} so alerts are delivered in the operator's Language_Preference;
     * when absent (e.g. the no-arg test constructor), subscription falls back to the untranslated
     * English channel so existing behavior and callers are unaffected.
     */
    private final TranslationService translationService;
    private final LanguagePreferenceService languagePreferenceService;

    /**
     * No-arg constructor preserving the original untranslated behavior. Used by callers and tests
     * that do not wire the translation layer; {@link #subscribe(String)} degrades to the plain
     * English channel under this construction.
     */
    public LivePushService() {
        this(null, null);
    }

    /**
     * Spring-injected constructor wiring the outbound-translation collaborators so
     * {@link #subscribe(String)} can build a per-operator {@link TranslatingLiveEventSink} around the
     * {@link SseEmitterSink}. Both dependencies are optional; a deployment without the translation
     * layer configured still constructs a working push service.
     */
    @Autowired
    public LivePushService(
            TranslationService translationService, LanguagePreferenceService languagePreferenceService) {
        this.translationService = translationService;
        this.languagePreferenceService = languagePreferenceService;
    }

    /**
     * Registers a new SSE subscriber and returns its {@link SseEmitter} for the controller to hand
     * back to Spring MVC. The emitter is deregistered automatically on completion, timeout, or error
     * so the registry never leaks dead connections.
     *
     * <p>This no-arg overload registers the untranslated English channel; use
     * {@link #subscribe(String)} to deliver alerts in a specific operator's Language_Preference.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        registerWithLifecycle(emitter, new SseEmitterSink(emitter));
        return emitter;
    }

    /**
     * Registers a new SSE subscriber for a specific Operator, delivering live alerts in that
     * operator's {@code Language_Preference} (Req 2.1, 2.6). When the translation collaborators are
     * available, the {@link SseEmitterSink} transport is wrapped in a {@link TranslatingLiveEventSink}
     * resolved against the operator's preference; otherwise it degrades to the plain English channel.
     *
     * @param operatorId the subscribing operator whose Language_Preference governs alert translation;
     *                   when {@code null} the untranslated channel is registered
     * @return the {@link SseEmitter} for the controller to return to Spring MVC
     */
    public SseEmitter subscribe(String operatorId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        LiveEventSink transport = new SseEmitterSink(emitter);
        registerWithLifecycle(emitter, wrapForOperator(transport, operatorId));
        return emitter;
    }

    /**
     * Wraps the transport sink in a per-operator {@link TranslatingLiveEventSink} when the
     * translation layer is available and an operator identity is supplied; otherwise returns the
     * transport unchanged (untranslated English channel).
     */
    private LiveEventSink wrapForOperator(LiveEventSink transport, String operatorId) {
        if (translationService == null || languagePreferenceService == null || operatorId == null) {
            return transport;
        }
        LanguageTag preference = languagePreferenceService.getPreference(operatorId);
        return new TranslatingLiveEventSink(transport, preference, translationService);
    }

    /**
     * Registers {@code sink} and wires the {@code emitter} lifecycle callbacks so the sink is
     * deregistered on completion, timeout, or error and the registry never leaks dead connections.
     */
    private void registerWithLifecycle(SseEmitter emitter, LiveEventSink sink) {
        register(sink);
        emitter.onCompletion(() -> unregister(sink));
        emitter.onTimeout(() -> {
            unregister(sink);
            emitter.complete();
        });
        emitter.onError(e -> unregister(sink));
    }

    /** Registers a sink to receive subsequent live events. */
    public void register(LiveEventSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        sinks.addIfAbsent(sink);
    }

    /** Deregisters a sink so it no longer receives events. */
    public void unregister(LiveEventSink sink) {
        sinks.remove(sink);
    }

    /** The number of currently-subscribed clients (for tests and diagnostics). */
    public int subscriberCount() {
        return sinks.size();
    }

    /** Pushes an Intent_Session lifecycle event to every subscribed client (Req 12.6). */
    public void publishSessionUpdate(SessionUpdateEvent event) {
        publish(LiveEvent.session(event));
    }

    /** Pushes a Divergence_Score event to every subscribed client (Req 12.6). */
    public void publishScore(ScoreEvent event) {
        publish(LiveEvent.score(event));
    }

    /** Pushes an {@link AlertEvent} to every subscribed client (Req 12.6). */
    public void publishAlert(AlertEvent event) {
        publish(LiveEvent.alert(event));
    }

    /** Pushes a session-anomaly (hijack) alert to every subscribed client (Req 10.x, 12.6). */
    public void publishAlert(SessionAnomalyAlert alert) {
        publishAlert(AlertEvent.fromSessionAnomaly(alert));
    }

    /** Pushes a monitoring-gap alert to every subscribed client (Req 1.4, 12.6). */
    public void publishAlert(MonitoringGapAlert alert) {
        publishAlert(AlertEvent.fromMonitoringGap(alert));
    }

    /**
     * Fans one envelope out to all registered sinks immediately. A sink that fails to receive (its
     * client has gone away) is dropped from the registry; a failed delivery to one client never
     * prevents delivery to the others.
     */
    public void publish(LiveEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        for (LiveEventSink sink : sinks) {
            try {
                sink.send(event);
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping live subscriber after failed delivery: {}", e.toString());
                unregister(sink);
            }
        }
    }
}
