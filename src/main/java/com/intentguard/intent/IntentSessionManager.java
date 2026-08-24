package com.intentguard.intent;

import java.util.Optional;

import com.intentguard.domain.Actor;

/**
 * Opens, closes, and modifies Intent_Sessions and enforces the invariant that an Agent_Actor can
 * never mutate intent (Req 4, Req 13.3).
 *
 * <p>Any {@code open}/{@code close}/{@code modify} request originating from an Agent_Actor is
 * rejected with an {@link AgentIntentMutationException}, the affected session is left unchanged, and
 * the rejected attempt is recorded in the Audit_History. Human requests open a session (recording
 * intent text, user, and start timestamp), record an end timestamp on close, apply the change on
 * modify, and persist the session.
 */
public interface IntentSessionManager {

    /**
     * Opens a new Intent_Session for {@code user} with the given Engine_Language (English)
     * Declared_Intent text (Req 4.1). The original Source_Text is left {@code null} and the language
     * tag defaults to English; use {@link #open(String, String, String, String, Actor)} when the
     * intent was submitted in a non-English Supported_Language.
     *
     * @throws AgentIntentMutationException if {@code actor} is an Agent_Actor (Req 13.3)
     */
    IntentSession open(String user, String declaredIntent, Actor actor);

    /**
     * Opens a new Intent_Session for {@code user} recording both the Engine_Language (English)
     * command text the engine scores and the untranslated Source_Text with its language tag
     * (Req 3.1, 3.2, 10.4).
     *
     * <p>The engine always scores and audits {@code declaredIntent} (English), independent of the
     * Operator's Language_Preference (Req 7.2, 7.3); {@code originalDeclaredIntent} and
     * {@code declaredIntentLanguageTag} retain the operator-submitted Source_Text and its BCP-47 tag
     * so an audit query can return both (Req 10.4).
     *
     * @param user                      the human user that declared the intent
     * @param declaredIntent            the Engine_Language (English) natural-language goal text
     * @param originalDeclaredIntent    the untranslated Source_Text, or {@code null} for English
     * @param declaredIntentLanguageTag the BCP-47 tag of the original intent; blank/{@code null}
     *                                  defaults to {@code "en"}
     * @param actor                     the requesting actor
     * @throws AgentIntentMutationException if {@code actor} is an Agent_Actor (Req 13.3)
     */
    IntentSession open(
            String user,
            String declaredIntent,
            String originalDeclaredIntent,
            String declaredIntentLanguageTag,
            Actor actor);

    /**
     * Closes the session identified by {@code sessionId}, recording its end timestamp and marking it
     * closed (Req 4.3).
     *
     * @throws AgentIntentMutationException if {@code actor} is an Agent_Actor (Req 13.3)
     */
    void close(String sessionId, Actor actor);

    /** Returns the currently open session for {@code user}, if any (Req 4.2). */
    Optional<IntentSession> activeSessionFor(String user);

    /**
     * Applies a modification to the Declared_Intent of an open session (Req 4).
     *
     * @throws AgentIntentMutationException if {@code actor} is an Agent_Actor; the session is left
     *                                      unchanged (Req 13.3)
     */
    void modify(String sessionId, IntentChange change, Actor actor);
}
