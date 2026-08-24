package com.intentguard.intent;

import java.util.Objects;

import com.intentguard.domain.IntentSource;

/**
 * A period during which a user has declared a natural-language goal that authorizes subsequent
 * Command_Events (Req 4.1). This is the in-memory domain view of a persisted Intent_Session; it is
 * mapped to and from {@link com.intentguard.persistence.IntentSessionDocument} at the persistence
 * boundary.
 *
 * <p>Session ids and user ids are represented pragmatically as {@code String}s. An open session has
 * {@code open == true} and {@code endedAt == null}; a closed session records its {@code endedAt}
 * end timestamp and has {@code open == false} (Req 4.3).
 *
 * <p>The {@code declaredIntent} is always the Engine_Language (English) command text the
 * Enforcement_Engine scores and audits, independent of any Operator's Language_Preference
 * (Req 7.2, 7.3). For an intent submitted in a non-English Supported_Language the
 * {@code originalDeclaredIntent} retains the untranslated Source_Text and
 * {@code declaredIntentLanguageTag} records its BCP-47 language tag (Req 3.2, 10.4); for an
 * English submission {@code originalDeclaredIntent} may be {@code null} (or equal to
 * {@code declaredIntent}) and {@code declaredIntentLanguageTag} defaults to {@code "en"}.
 *
 * @param sessionId                 the business key identifying the session
 * @param userId                    the human user that declared the intent
 * @param declaredIntent            the Engine_Language (English) natural-language goal text scored
 *                                  by the engine
 * @param originalDeclaredIntent    the untranslated Source_Text, or {@code null} for an English
 *                                  submission
 * @param declaredIntentLanguageTag the BCP-47 language tag of the original Declared_Intent (for
 *                                  example {@code "hi"}); defaults to {@code "en"}
 * @param intentSource              provenance of the intent (DECLARED for a human-opened session)
 * @param startedAt                 the start timestamp (UTC epoch millis)
 * @param endedAt                   the end timestamp (UTC epoch millis), or {@code null} while open
 * @param open                      whether the session is currently open
 */
public record IntentSession(
        String sessionId,
        String userId,
        String declaredIntent,
        String originalDeclaredIntent,
        String declaredIntentLanguageTag,
        IntentSource intentSource,
        long startedAt,
        Long endedAt,
        boolean open) {

    /** Default Engine_Language tag applied when a session records no explicit source language. */
    public static final String DEFAULT_LANGUAGE_TAG = "en";

    public IntentSession {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(intentSource, "intentSource must not be null");
        if (declaredIntentLanguageTag == null || declaredIntentLanguageTag.isBlank()) {
            declaredIntentLanguageTag = DEFAULT_LANGUAGE_TAG;
        }
    }

    /**
     * Backward-compatible constructor for English-only callers that predate inbound translation.
     * The original Source_Text is left {@code null} and the language tag defaults to English
     * ({@value #DEFAULT_LANGUAGE_TAG}), preserving all existing behavior (Req 7.2, 7.3).
     */
    public IntentSession(
            String sessionId,
            String userId,
            String declaredIntent,
            IntentSource intentSource,
            long startedAt,
            Long endedAt,
            boolean open) {
        this(
                sessionId,
                userId,
                declaredIntent,
                null,
                DEFAULT_LANGUAGE_TAG,
                intentSource,
                startedAt,
                endedAt,
                open);
    }
}
