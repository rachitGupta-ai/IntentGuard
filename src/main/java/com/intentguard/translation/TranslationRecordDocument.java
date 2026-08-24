package com.intentguard.translation;

/**
 * Persisted Translation_Record for the {@code translation_records} collection (Req 10.1, 10.3). It
 * captures the full provenance of a translation, recognition, or synthesis: the Source_Text, the
 * Translated_Text, the source and target {@code Supported_Language} tags, the provider identity,
 * the {@link TranslationRecordKind}, and the timestamp.
 *
 * <p>The {@code sourceLanguageTag} and {@code targetLanguageTag} are stored as their BCP-47 string
 * values and {@code kind} is stored as its {@code name()} string, keeping the POJO codec mapping
 * simple and forward-compatible with new {@link TranslationRecordKind} values — mirroring how
 * {@code AuditHistoryDocument} stores its enum-valued fields.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class TranslationRecordDocument {

    /** The Mongo collection name backing this document (Req 10.1). */
    public static final String COLLECTION = "translation_records";

    private String sourceText;
    private String translatedText;
    private String sourceLanguageTag;
    private String targetLanguageTag;
    private String providerId;
    private String kind;
    private long timestamp;

    public TranslationRecordDocument() {
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public void setTranslatedText(String translatedText) {
        this.translatedText = translatedText;
    }

    public String getSourceLanguageTag() {
        return sourceLanguageTag;
    }

    public void setSourceLanguageTag(String sourceLanguageTag) {
        this.sourceLanguageTag = sourceLanguageTag;
    }

    public String getTargetLanguageTag() {
        return targetLanguageTag;
    }

    public void setTargetLanguageTag(String targetLanguageTag) {
        this.targetLanguageTag = targetLanguageTag;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
