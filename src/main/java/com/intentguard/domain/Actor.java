package com.intentguard.domain;

import java.util.Objects;

/**
 * The originating entity of a {@code CommandEvent} or an intent request.
 *
 * @param type            whether the actor is a {@code HUMAN} or an {@code AGENT}
 * @param userId          the identity under which the action was observed (OS user / session id)
 * @param humanPrincipalId for an {@code AGENT}, the human principal whose Intent_Session envelope
 *                         bounds it; {@code null} for a {@code HUMAN} actor
 */
public record Actor(ActorType type, String userId, String humanPrincipalId) {

    public Actor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }

    /** Convenience factory for a human actor. */
    public static Actor human(String userId) {
        return new Actor(ActorType.HUMAN, userId, null);
    }

    /** Convenience factory for an AI agent acting on behalf of a human principal. */
    public static Actor agent(String userId, String humanPrincipalId) {
        return new Actor(ActorType.AGENT, userId, humanPrincipalId);
    }

    public boolean isAgent() {
        return type == ActorType.AGENT;
    }

    public boolean isHuman() {
        return type == ActorType.HUMAN;
    }
}
