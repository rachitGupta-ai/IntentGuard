package com.intentguard.speech;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.intentguard.llm.LlmProperties;
import com.intentguard.translation.LanguageTag;

/**
 * Test helper that bridges the package-private {@code GeminiSpeechProvider} test constructor for
 * cross-package lazy-initialization tests.
 *
 * <p>Exposes {@code recognize} and {@code synthesize} calls through a public API so that the
 * {@code GeminiLazyClientConstructionTest} in the {@code translation} package can verify lazy
 * client construction and single-instance guarantees for the speech provider.
 */
public final class GeminiSpeechProviderLazyAccessor {

    private static final AudioClip AUDIO =
            AudioClip.of("audio-data".getBytes(StandardCharsets.UTF_8), "audio/wav");

    private final GeminiSpeechProvider provider;

    public GeminiSpeechProviderLazyAccessor(LlmProperties llmProperties, AtomicInteger counter) {
        SpeechProperties speechProps = new SpeechProperties();
        speechProps.setApiKey("test-api-key");
        speechProps.setSttTimeoutMs(10000);
        speechProps.setTtsTimeoutMs(5000);

        this.provider = new GeminiSpeechProvider(llmProperties, speechProps, prompt -> {
            counter.incrementAndGet();
            return "recognized-text";
        });
    }

    /**
     * Delegates to {@link GeminiSpeechProvider#recognize(AudioClip, LanguageTag)} with a
     * fixed audio clip.
     */
    public Optional<RecognizedText> recognize(LanguageTag language) {
        return provider.recognize(AUDIO, language);
    }

    /**
     * Delegates to {@link GeminiSpeechProvider#synthesize(String, LanguageTag)}.
     */
    public Optional<AudioClip> synthesize(LanguageTag language) {
        return provider.synthesize("test text", language);
    }
}
