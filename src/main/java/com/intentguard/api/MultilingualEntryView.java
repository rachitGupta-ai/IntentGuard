package com.intentguard.api;

import java.util.Optional;

import com.intentguard.persistence.IntentSessionDocument;
import com.intentguard.translation.LanguageTag;
import com.intentguard.translation.SupportedLanguages;

/**
 * Projected view of a multilingual command/intent entry derived from an {@link IntentSessionDocument}.
 *
 * <p>A session becomes a {@code MultilingualEntryView} only when:
 * <ul>
 *   <li>the session is attributable to a user (non-blank {@code userId}),</li>
 *   <li>{@code originalDeclaredIntent} is present and non-blank,</li>
 *   <li>{@code declaredIntentLanguageTag} is a non-English {@link SupportedLanguages} member.</li>
 * </ul>
 * Sessions that do not satisfy all three conditions are excluded (Req 3.4).
 *
 * <p>Text fields ({@link #sourceText} and {@link #englishText}) are copied byte-for-byte from the
 * persisted document, preserving technical tokens (paths, commands, IPs) without any alteration
 * (Req 3.3).
 *
 * @param sessionId            the session identifier
 * @param sourceText           the original non-English declared intent ({@code originalDeclaredIntent}),
 *                             verbatim — never null here (Req 3.1, 3.3)
 * @param sourceLanguageTag    BCP-47 tag of the source language (non-English supported language)
 * @param englishText          the English translation ({@code declaredIntent}), or {@code null}
 *                             when the translation is absent (Req 3.6)
 * @param translationAvailable {@code false} when {@code declaredIntent} was absent or blank (Req 3.6)
 * @param timestamp            session start time as epoch milliseconds ({@code startedAt})
 */
public record MultilingualEntryView(
        String sessionId,
        String sourceText,
        String sourceLanguageTag,
        String englishText,
        boolean translationAvailable,
        long timestamp) {

    /**
     * Projects an {@link IntentSessionDocument} to a {@code MultilingualEntryView}.
     *
     * <p>Returns {@link Optional#empty()} when the session is not attributable (Req 3.4),
     * {@code originalDeclaredIntent} is blank (Req 3.1), or {@code declaredIntentLanguageTag} is
     * English or not a member of the provided {@link SupportedLanguages} set (Req 3.1).
     *
     * <p>Text fields are copied verbatim to preserve technical tokens byte-for-byte (Req 3.3).
     *
     * @param s     the persisted session document
     * @param langs the configured Supported_Language set used for the non-English membership check
     * @return a populated view wrapped in {@link Optional}, or empty when excluded
     */
    public static Optional<MultilingualEntryView> from(IntentSessionDocument s, SupportedLanguages langs) {
        // Req 3.4: session must be attributable to a user.
        if (s.getUserId() == null || s.getUserId().isBlank()) {
            return Optional.empty();
        }

        // Req 3.1: source text must be present and non-blank.
        if (s.getOriginalDeclaredIntent() == null || s.getOriginalDeclaredIntent().isBlank()) {
            return Optional.empty();
        }

        // Req 3.1: language tag must be a non-English Supported_Language.
        String tagValue = s.getDeclaredIntentLanguageTag();
        if (tagValue == null || tagValue.isBlank()) {
            return Optional.empty();
        }
        LanguageTag tag;
        try {
            tag = LanguageTag.of(tagValue);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (SupportedLanguages.ENGLISH.equals(tag) || !langs.isSupported(tag)) {
            return Optional.empty();
        }

        // Req 3.6: translationAvailable is true only when declaredIntent (English text) is present.
        String englishText = s.getDeclaredIntent();
        boolean translationAvailable = englishText != null && !englishText.isBlank();

        // Req 3.3: copy text fields verbatim — no trimming, no transformation.
        return Optional.of(new MultilingualEntryView(
                s.getSessionId(),
                s.getOriginalDeclaredIntent(),
                tag.value(),
                translationAvailable ? englishText : null,
                translationAvailable,
                s.getStartedAt()));
    }
}
