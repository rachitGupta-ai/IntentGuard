package com.intentguard.translation;

import java.util.Objects;

/**
 * The persisted association of a {@code Source_Text}, its {@code Translated_Text}, the source and
 * target {@code Supported_Language} tags, the Translation_Provider / Speech_Provider identity, and
 * the {@link TranslationRecordKind} of the operation (Req 10.1, 8.7).
 *
 * <p>This is the in-memory, immutable projection of the {@code translation_records} collection; its
 * mutable Mongo POJO counterpart is {@link TranslationRecordDocument}. A record is written on a
 * successful translation, recognition, or synthesis; a persistence failure never blocks presenting
 * the Translated_Text (Req 10.2).
 *
 * @param sourceText        the original text supplied to the translation, in its source language
 * @param translatedText    the produced Translated_Text
 * @param sourceLanguageTag the source {@code Supported_Language} tag
 * @param targetLanguageTag the target {@code Supported_Language} tag
 * @param providerId        the identity of the Translation_Provider or Speech_Provider used
 * @param kind              the kind of operation the record captures
 * @param timestamp         the UTC epoch-millis instant the operation completed
 */
public record TranslationRecord(
        String sourceText,
        String translatedText,
        LanguageTag sourceLanguageTag,
        LanguageTag targetLanguageTag,
        String providerId,
        TranslationRecordKind kind,
        long timestamp) {

    public TranslationRecord {
        Objects.requireNonNull(sourceText, "sourceText must not be null");
        Objects.requireNonNull(translatedText, "translatedText must not be null");
        Objects.requireNonNull(sourceLanguageTag, "sourceLanguageTag must not be null");
        Objects.requireNonNull(targetLanguageTag, "targetLanguageTag must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}
