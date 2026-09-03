package com.intentguard.api;

import com.intentguard.translation.TranslationRecord;

/**
 * Read-only projection of a {@link TranslationRecord} for the User_Profiling_Screen
 * translations category (Req 5.2, 5.5).
 *
 * <p>Language tags are surfaced as their normalized BCP-47 string values (e.g. {@code "hi"},
 * {@code "en"}) via {@link com.intentguard.translation.LanguageTag#value()}.
 *
 * <p>{@code kind} is the {@link com.intentguard.translation.TranslationRecordKind} name string
 * (e.g. {@code "INBOUND_INTENT"}, {@code "OUTBOUND_CONTENT"}, {@code "STT"}, {@code "TTS"})
 * (Req 5.2).
 *
 * <p>{@code degraded} is {@code true} when the record looks like a fallback-to-English or no-op
 * translation — detected when {@code sourceLanguageTag == targetLanguageTag} OR
 * {@code translatedText.equals(sourceText)} (Req 5.5). Because truly failed/timed-out
 * translations are never persisted, this flag surfaces only the fallback cases that were written.
 *
 * @param sourceText         the original text (already PII-masked at write time)
 * @param translatedText     the produced translation / recognized / synthesized text
 * @param sourceLanguageTag  BCP-47 source language tag value (e.g. {@code "hi"})
 * @param targetLanguageTag  BCP-47 target language tag value (e.g. {@code "en"})
 * @param kind               operation kind name: INBOUND_INTENT | OUTBOUND_CONTENT | STT | TTS (Req 5.2)
 * @param degraded           true when sourceLanguageTag == targetLanguageTag OR translatedText == sourceText (Req 5.5)
 * @param timestamp          epoch-millis when the operation completed
 */
public record TranslationRecordView(
        String sourceText,
        String translatedText,
        String sourceLanguageTag,
        String targetLanguageTag,
        String kind,
        boolean degraded,
        long timestamp) {

    /**
     * Projects a {@link TranslationRecord} into a {@link TranslationRecordView}.
     *
     * <p>Text fields are copied verbatim from the record; no decoding or unmasking is performed
     * (Req 9.5). The {@code degraded} flag is computed from the tag and text comparisons (Req 5.5).
     *
     * @param r the source record; must not be null
     * @return the projected view
     */
    public static TranslationRecordView from(TranslationRecord r) {
        String srcTag = r.sourceLanguageTag().value();
        String tgtTag = r.targetLanguageTag().value();
        boolean degraded = srcTag.equals(tgtTag) || r.translatedText().equals(r.sourceText());
        return new TranslationRecordView(
                r.sourceText(),
                r.translatedText(),
                srcTag,
                tgtTag,
                r.kind().name(),
                degraded,
                r.timestamp());
    }
}
