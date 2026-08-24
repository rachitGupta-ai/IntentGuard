package com.intentguard.api;

/**
 * A live "session" event pushed to subscribed Control_Tower clients when an Intent_Session is
 * opened, modified, or closed (Req 12.6). It lets the dashboard keep the "active Intent_Sessions"
 * view current without polling.
 *
 * @param sessionId      the Intent_Session id
 * @param userId         the human principal that owns the session
 * @param declaredIntent the Declared_Intent text (may be {@code null} on close)
 * @param status         the lifecycle transition: {@code OPENED} / {@code MODIFIED} /
 *                       {@code CLOSED}
 * @param timestamp      UTC epoch millis of the transition
 */
public record SessionUpdateEvent(
        String sessionId,
        String userId,
        String declaredIntent,
        String status,
        long timestamp) {
}
