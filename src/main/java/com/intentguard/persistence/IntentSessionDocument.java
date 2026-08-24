package com.intentguard.persistence;

/**
 * Persisted Intent_Session for the {@code intent_sessions} collection (Req 4.1, 4.5).
 * {@link #sessionId} is the business key; {@link #open} distinguishes an active session from a
 * closed one ({@link #endedAt} set).
 *
 * <p>{@link #declaredIntent} is always the Engine_Language (English) command text scored and
 * audited by the Enforcement_Engine, independent of any Operator's Language_Preference (Req 7.2,
 * 7.3). {@link #originalDeclaredIntent} retains the untranslated Source_Text and
 * {@link #declaredIntentLanguageTag} its BCP-47 tag when the intent was submitted in a non-English
 * Supported_Language (Req 3.2, 10.4); for an English submission the original may be {@code null}
 * and the tag defaults to {@code "en"}.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class IntentSessionDocument {

    private String sessionId;
    private String userId;
    private String declaredIntent;
    private String originalDeclaredIntent;
    private String declaredIntentLanguageTag;
    private String intentSource;
    private long startedAt;
    private Long endedAt;
    private boolean open;

    public IntentSessionDocument() {
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDeclaredIntent() {
        return declaredIntent;
    }

    public void setDeclaredIntent(String declaredIntent) {
        this.declaredIntent = declaredIntent;
    }

    public String getOriginalDeclaredIntent() {
        return originalDeclaredIntent;
    }

    public void setOriginalDeclaredIntent(String originalDeclaredIntent) {
        this.originalDeclaredIntent = originalDeclaredIntent;
    }

    public String getDeclaredIntentLanguageTag() {
        return declaredIntentLanguageTag;
    }

    public void setDeclaredIntentLanguageTag(String declaredIntentLanguageTag) {
        this.declaredIntentLanguageTag = declaredIntentLanguageTag;
    }

    public String getIntentSource() {
        return intentSource;
    }

    public void setIntentSource(String intentSource) {
        this.intentSource = intentSource;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Long endedAt) {
        this.endedAt = endedAt;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }
}
