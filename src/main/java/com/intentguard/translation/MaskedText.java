package com.intentguard.translation;

import java.util.List;

/**
 * The result of masking a Source_Text for translation: the {@link #masked() masked} string in which
 * every detected {@code Technical_Token} has been replaced by an opaque, translation-stable sentinel
 * (for example {@code ⟦IG0⟧}), together with the ordered {@link #tokens() tokens} that were removed.
 *
 * <p>The list is positional: the token at index {@code i} was replaced by the sentinel carrying
 * index {@code i}, so {@link TechnicalTokenProtector#restore(String, MaskedText)} can substitute the
 * exact original bytes back into a translated string regardless of how a Translation_Provider or
 * Speech_Provider rewrote the surrounding prose (Req 2.3, 7.1, 7.4).
 *
 * @param masked the Source_Text with each Technical_Token replaced by its sentinel
 * @param tokens the removed Technical_Tokens, indexed to match their sentinels
 */
public record MaskedText(String masked, List<String> tokens) {

    public MaskedText {
        if (masked == null) {
            throw new IllegalArgumentException("masked must not be null");
        }
        // Defensive copy so a MaskedText is immutable and safe to hold across the provider call.
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    /**
     * Whether any Technical_Token was detected and masked.
     *
     * @return {@code true} when at least one token was removed
     */
    public boolean hasTokens() {
        return !tokens.isEmpty();
    }
}
