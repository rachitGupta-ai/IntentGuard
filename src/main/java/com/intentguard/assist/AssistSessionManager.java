package com.intentguard.assist;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intentguard.domain.Actor;
import com.intentguard.intent.IntentSessionManager;

/**
 * Manages {@link AssistSession} lifecycle: creation, lookup, update, close, and idle-timeout
 * eviction.
 *
 * <p>Sessions are stored in a {@link ConcurrentHashMap} — consistent with the project's
 * single-process architecture. A scheduled task evicts sessions that have been idle beyond the
 * configured timeout (Req 7.4, 7.5). When a session is closed (explicitly or by eviction), its
 * associated {@code Intent_Session} is also closed via {@link IntentSessionManager} (Req 4.4).
 */
@Component
public class AssistSessionManager {

    private final ConcurrentHashMap<String, AssistSession> sessions = new ConcurrentHashMap<>();
    private final AssistProperties properties;
    private final IntentSessionManager intentSessionManager;
    private volatile Clock clock = Clock.systemUTC();

    public AssistSessionManager(AssistProperties properties, IntentSessionManager intentSessionManager) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.intentSessionManager = Objects.requireNonNull(intentSessionManager,
                "intentSessionManager must not be null");
    }

    /** Test seam: overrides the clock used for session timestamps. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns an existing session or creates a new one.
     *
     * <p>If a session with the given ID already exists, validates that the operator matches and
     * returns it. Otherwise creates a fresh session bound to the given operator (Req 7.1, 7.3).
     *
     * @param sessionId  unique session identifier
     * @param operatorId the operator requesting the session
     * @return the existing or newly created session
     * @throws IllegalArgumentException if the session exists but belongs to a different operator
     */
    public AssistSession getOrCreate(String sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");

        return sessions.compute(sessionId, (id, existing) -> {
            if (existing != null) {
                if (!existing.operatorId().equals(operatorId)) {
                    throw new IllegalArgumentException(
                            "Session " + id + " belongs to a different operator");
                }
                return existing;
            }
            long now = clock.millis();
            return new AssistSession(id, operatorId, null, List.of(), List.of(), null,
                    now, now, true);
        });
    }

    /**
     * Returns the session with the given ID, or empty if not found.
     *
     * @param sessionId the session identifier to look up
     * @return the session, or empty
     */
    public Optional<AssistSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Replaces the stored session state. The session must already exist (via {@link #getOrCreate}).
     *
     * @param session the updated session state
     */
    public void update(AssistSession session) {
        Objects.requireNonNull(session, "session must not be null");
        sessions.put(session.sessionId(), session);
    }

    /**
     * Closes and removes the session. If the session has an associated {@code Intent_Session},
     * it is also closed via the {@link IntentSessionManager} (Req 4.4).
     *
     * @param sessionId the session to close
     */
    public void close(String sessionId) {
        AssistSession removed = sessions.remove(sessionId);
        if (removed != null && removed.intentSessionId() != null) {
            intentSessionManager.close(removed.intentSessionId(),
                    Actor.human(removed.operatorId()));
        }
    }

    /**
     * Periodic cleanup of idle sessions. Closes any session whose last activity plus the configured
     * timeout is before the current time (Req 7.4, 7.5).
     *
     * <p>Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdleSessions() {
        long now = clock.millis();
        long timeout = properties.getSessionTimeoutMs();
        sessions.forEach((id, session) -> {
            if ((now - session.lastActivityAt()) > timeout) {
                close(id);
            }
        });
    }
}
