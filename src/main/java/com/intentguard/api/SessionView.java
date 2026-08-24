package com.intentguard.api;

import com.intentguard.intent.IntentSession;
import com.intentguard.persistence.IntentSessionDocument;

/**
 * Read model of an Intent_Session returned by {@link IntentSessionController} session queries. It
 * exposes the original Declared_Intent Source_Text alongside its Engine_Language (English)
 * translation so an audit query returns both texts (Req 10.4).
 *
 * <p>{@code declaredIntent} is always the English command text the Enforcement_Engine scores;
 * {@code originalDeclaredIntent} is the untranslated Source_Text ({@code null} for an English
 * submission) and {@code declaredIntentLanguageTag} its BCP-47 tag (defaulting to {@code "en"}).
 *
 * @param sessionId                 the session business key
 * @param userId                    the human user that declared the intent
 * @param declaredIntent            the Engine_Language (English) Declared_Intent text
 * @param originalDeclaredIntent    the untranslated Source_Text, or {@code null} for English
 * @param declaredIntentLanguageTag the BCP-47 tag of the original Declared_Intent
 * @param intentSource              provenance of the intent
 * @param startedAt                 start timestamp (UTC epoch millis)
 * @param endedAt                   end timestamp (UTC epoch millis), or {@code null} while open
 * @param open                      whether the session is currently open
 */
public record SessionView(
        String sessionId,
        String userId,
        String declaredIntent,
        String originalDeclaredIntent,
        String declaredIntentLanguageTag,
        String intentSource,
        long startedAt,
        Long endedAt,
        boolean open) {

    /** Builds a view from the in-memory {@link IntentSession} domain object. */
    public static SessionView from(IntentSession session) {
        return new SessionView(
                session.sessionId(),
                session.userId(),
                session.declaredIntent(),
                session.originalDeclaredIntent(),
                session.declaredIntentLanguageTag(),
                session.intentSource().name(),
                session.startedAt(),
                session.endedAt(),
                session.open());
    }

    /** Builds a view from the persisted {@link IntentSessionDocument}. */
    public static SessionView from(IntentSessionDocument document) {
        return new SessionView(
                document.getSessionId(),
                document.getUserId(),
                document.getDeclaredIntent(),
                document.getOriginalDeclaredIntent(),
                document.getDeclaredIntentLanguageTag(),
                document.getIntentSource(),
                document.getStartedAt(),
                document.getEndedAt(),
                document.isOpen());
    }
}
