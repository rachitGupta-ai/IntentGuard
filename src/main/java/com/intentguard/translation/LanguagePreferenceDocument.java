package com.intentguard.translation;

/**
 * Persisted per-operator {@code Language_Preference} for the {@code language_preferences} collection
 * (Req 1.4). Each document is keyed by {@code operatorId} and upserted in place so that changing a
 * preference updates the same document, and the selection survives Control_Tower sessions and
 * engine restarts.
 *
 * <p>The {@code languageTag} is stored as its BCP-47 string value, mirroring how
 * {@link TranslationRecordDocument} stores its language tags and keeping the POJO codec mapping
 * simple.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class LanguagePreferenceDocument {

    /** The Mongo collection name backing this document (Req 1.4). */
    public static final String COLLECTION = "language_preferences";

    private String operatorId;
    private String languageTag;
    private long updatedAt;

    public LanguagePreferenceDocument() {
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getLanguageTag() {
        return languageTag;
    }

    public void setLanguageTag(String languageTag) {
        this.languageTag = languageTag;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
