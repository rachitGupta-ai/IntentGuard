package com.intentguard.api;

import java.util.Objects;

/**
 * The unified envelope for every event pushed over the Control_Tower live channel (Req 12.6). A
 * single stream carries session, score, and alert events; the {@code type} discriminator tells the
 * client which kind of {@code payload} it holds so it can update the corresponding dashboard view.
 *
 * @param type      the event kind: {@code SESSION}, {@code SCORE}, or {@code ALERT}
 * @param timestamp UTC epoch millis the envelope was created
 * @param payload   the typed payload ({@link SessionUpdateEvent}, {@link ScoreEvent}, or
 *                  {@link AlertEvent})
 */
public record LiveEvent(String type, long timestamp, Object payload) {

    public static final String TYPE_SESSION = "SESSION";
    public static final String TYPE_SCORE = "SCORE";
    public static final String TYPE_ALERT = "ALERT";

    public LiveEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static LiveEvent session(SessionUpdateEvent event) {
        return new LiveEvent(TYPE_SESSION, event.timestamp(), event);
    }

    public static LiveEvent score(ScoreEvent event) {
        return new LiveEvent(TYPE_SCORE, event.timestamp(), event);
    }

    public static LiveEvent alert(AlertEvent event) {
        return new LiveEvent(TYPE_ALERT, event.timestamp(), event);
    }
}
