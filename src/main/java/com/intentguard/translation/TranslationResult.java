package com.intentguard.translation;

import java.util.Objects;

/**
 * The result of a {@link TranslationService#translate} call: the text the Control_Tower should
 * present, whether it is an actual translation, and the {@link TranslationOutcome outcome} that
 * classifies which path produced it.
 *
 * <p>{@link #text()} is always safe to present: it is the {@code Translated_Text} on a successful
 * translation (or cache reuse) and the original English / {@code Source_Text} on every fall-through
 * path, honoring the feature's <strong>fail to English, never block the operator</strong> rule.
 * {@link #translated()} is {@code true} only when {@link #text()} is a machine translation
 * ({@link TranslationOutcome#TRANSLATED} or {@link TranslationOutcome#CACHED}); it is {@code false}
 * on every path that returns the input unchanged.
 *
 * @param text       the text to present — Translated_Text, or the original text on any fall-through
 * @param translated {@code true} only when {@code text} is a machine translation
 * @param outcome    the classified path that produced this result
 */
public record TranslationResult(String text, boolean translated, TranslationOutcome outcome) {

    public TranslationResult {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
