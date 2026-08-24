package com.intentguard.translation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * A translation reuse cache keyed by {@code (Source_Text, target Supported_Language)} (Req 9.3).
 *
 * <p>When an identical {@code Source_Text} has already been translated into the same target
 * {@code Supported_Language}, the {@code TranslationService} consults this cache before issuing any
 * {@link TranslationProvider} request and reuses the previously produced {@code Translated_Text},
 * avoiding a redundant external network call.
 *
 * <p>Thread-safety: the cache is backed by a {@link ConcurrentHashMap} so concurrent
 * {@link #lookup} and {@link #store} calls from parallel Operator requests are safe without
 * external synchronization. Both the {@code Source_Text} and the target tag participate in the key,
 * so the same text translated into two different languages is cached independently.
 */
@Component
public class TranslationCache {

    private final ConcurrentMap<Key, String> entries = new ConcurrentHashMap<>();

    /**
     * Returns the previously produced {@code Translated_Text} for the given
     * {@code (Source_Text, targetLanguageTag)} pair, if one has been stored.
     *
     * @param sourceText        the original text supplied to the translation
     * @param targetLanguageTag the target {@code Supported_Language} tag
     * @return the prior {@code Translated_Text}, or {@link Optional#empty()} if none is cached
     */
    public Optional<String> lookup(String sourceText, LanguageTag targetLanguageTag) {
        return Optional.ofNullable(entries.get(new Key(sourceText, targetLanguageTag)));
    }

    /**
     * Stores the {@code Translated_Text} produced for the given
     * {@code (Source_Text, targetLanguageTag)} pair so subsequent identical requests reuse it
     * without a new {@link TranslationProvider} request.
     *
     * @param sourceText        the original text supplied to the translation
     * @param targetLanguageTag the target {@code Supported_Language} tag
     * @param translatedText    the produced {@code Translated_Text} to cache
     */
    public void store(String sourceText, LanguageTag targetLanguageTag, String translatedText) {
        Objects.requireNonNull(translatedText, "translatedText must not be null");
        entries.put(new Key(sourceText, targetLanguageTag), translatedText);
    }

    /**
     * The composite cache key. Both components are required and participate in equality so that the
     * same {@code Source_Text} cached for different target languages does not collide.
     */
    private record Key(String sourceText, LanguageTag targetLanguageTag) {
        private Key {
            Objects.requireNonNull(sourceText, "sourceText must not be null");
            Objects.requireNonNull(targetLanguageTag, "targetLanguageTag must not be null");
        }
    }
}
