package com.intentguard.intent;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of submitting an inbound Declared_Intent through {@link InboundIntentService}, so the
 * controller (and the inbound-intent property tests) can assert on the result without inspecting
 * translation internals (Req 3.1-3.4).
 *
 * <p>Exactly one of the two terminal states is produced:
 * <ul>
 *   <li>{@link Status#SESSION_OPENED} - translation to the Engine_Language succeeded (or the intent
 *       was already English) and the {@link IntentSession} was opened on the English text with both
 *       texts recorded (Req 3.1, 3.2, 10.4); {@link #session()} is present and {@link #message()} is
 *       empty.</li>
 *   <li>{@link Status#REJECTED} - the inbound translation timed out or errored, so the submission was
 *       rejected and <strong>no</strong> Intent_Session was opened; {@link #session()} is empty and
 *       {@link #message()} carries a prompt, in the Operator's Language_Preference, to retry or
 *       submit in English (Req 3.3, 3.4).</li>
 * </ul>
 *
 * @param status  the terminal outcome (never {@code null})
 * @param session the opened Intent_Session, present only for {@link Status#SESSION_OPENED}
 * @param message the localized retry/English prompt, present only for {@link Status#REJECTED}
 */
public record InboundIntentResult(Status status, IntentSession session, String message) {

    /** The terminal outcome of an inbound Declared_Intent submission. */
    public enum Status {
        /** The Intent_Session was opened on the Engine_Language text (Req 3.1, 3.2). */
        SESSION_OPENED,
        /** Translation failed; the submission was rejected with no session opened (Req 3.3, 3.4). */
        REJECTED
    }

    public InboundIntentResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * A session-opened result carrying the opened session (Req 3.1, 3.2).
     *
     * @param session the opened Intent_Session
     * @return a {@link Status#SESSION_OPENED} result
     */
    public static InboundIntentResult sessionOpened(IntentSession session) {
        return new InboundIntentResult(
                Status.SESSION_OPENED, Objects.requireNonNull(session, "session must not be null"), null);
    }

    /**
     * A rejection result carrying the localized retry/English prompt (Req 3.3, 3.4).
     *
     * @param message the operator-facing prompt in the Operator's Language_Preference
     * @return a {@link Status#REJECTED} result with no session
     */
    public static InboundIntentResult rejected(String message) {
        return new InboundIntentResult(
                Status.REJECTED, null, Objects.requireNonNull(message, "message must not be null"));
    }

    /** @return {@code true} when the Intent_Session was opened (Req 3.1). */
    public boolean opened() {
        return status == Status.SESSION_OPENED;
    }

    /** @return the opened Intent_Session, present only when {@link #opened()} is {@code true}. */
    public Optional<IntentSession> openedSession() {
        return Optional.ofNullable(session);
    }

    /** @return the localized rejection prompt, present only for a {@link Status#REJECTED} result. */
    public Optional<String> messageText() {
        return Optional.ofNullable(message);
    }
}
