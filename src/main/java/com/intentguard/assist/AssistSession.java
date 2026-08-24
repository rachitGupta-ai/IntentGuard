package com.intentguard.assist;

import com.intentguard.domain.Decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tracks multi-turn conversational state for a single operator interaction.
 *
 * <p>This record is immutable — all state transitions return a new instance via builder-style
 * {@code with*} methods. Lists are defensively copied on construction.
 *
 * @param sessionId            unique session identifier
 * @param operatorId           the operator who owns this session
 * @param intentSessionId      the associated Intent_Session ID (opened on first query)
 * @param history              ordered list of query-response-outcome entries
 * @param currentAlternatives  the alternatives from the most recent query
 * @param lastScoredDecision   the Decision from the most recent /select call (null if not yet selected)
 * @param createdAt            session creation timestamp (UTC millis)
 * @param lastActivityAt       timestamp of last interaction (UTC millis)
 * @param open                 whether the session is currently active
 */
public record AssistSession(
        String sessionId,
        String operatorId,
        String intentSessionId,
        List<AssistTurn> history,
        List<CommandAlternative> currentAlternatives,
        Decision lastScoredDecision,
        long createdAt,
        long lastActivityAt,
        boolean open) {

    public AssistSession {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        history = history == null ? List.of() : List.copyOf(history);
        currentAlternatives = currentAlternatives == null ? List.of() : List.copyOf(currentAlternatives);
    }

    /**
     * Returns a new session with the given Intent_Session ID.
     */
    public AssistSession withIntentSessionId(String intentSessionId) {
        return new AssistSession(sessionId, operatorId, intentSessionId, history,
                currentAlternatives, lastScoredDecision, createdAt, lastActivityAt, open);
    }

    /**
     * Returns a new session with the given current alternatives (replacing any previous).
     */
    public AssistSession withCurrentAlternatives(List<CommandAlternative> currentAlternatives) {
        return new AssistSession(sessionId, operatorId, intentSessionId, history,
                currentAlternatives, lastScoredDecision, createdAt, lastActivityAt, open);
    }

    /**
     * Returns a new session with the given last scored decision.
     */
    public AssistSession withLastScoredDecision(Decision lastScoredDecision) {
        return new AssistSession(sessionId, operatorId, intentSessionId, history,
                currentAlternatives, lastScoredDecision, createdAt, lastActivityAt, open);
    }

    /**
     * Returns a new session with the given turn appended to history.
     */
    public AssistSession withAddedTurn(AssistTurn turn) {
        List<AssistTurn> newHistory = new ArrayList<>(history);
        newHistory.add(turn);
        return new AssistSession(sessionId, operatorId, intentSessionId, newHistory,
                currentAlternatives, lastScoredDecision, createdAt, lastActivityAt, open);
    }

    /**
     * Returns a new session with the given last-activity timestamp.
     */
    public AssistSession withLastActivityAt(long lastActivityAt) {
        return new AssistSession(sessionId, operatorId, intentSessionId, history,
                currentAlternatives, lastScoredDecision, createdAt, lastActivityAt, open);
    }

    /**
     * Returns the English query text from the most recent turn, or {@code null} if the
     * history is empty.
     */
    public String currentIntentText() {
        if (history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1).queryEnglish();
    }
}
