package com.intentguard.speech;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.intentguard.translation.LanguageTag;

/**
 * In-memory {@link SpeechProvider} fake that always succeeds, for use as test support by later
 * Speech_Service tasks.
 *
 * <p>Recognition returns a caller-configured transcript (defaulting to a fixed phrase) in the
 * requested language; synthesis returns a deterministic {@link AudioClip} derived from the input
 * text so round-trip tests can assert on stable bytes. Following the {@code LlmService} contract it
 * never throws across the boundary.
 */
public final class FakeSuccessSpeechProvider implements SpeechProvider {

    /** MIME type used for synthesized clips produced by this fake. */
    public static final String SYNTHESIZED_MIME_TYPE = "audio/wav";

    private final String id;
    private final String recognizedTranscript;

    /**
     * Creates a fake with the default identity and a fixed recognized transcript.
     */
    public FakeSuccessSpeechProvider() {
        this("fake-success-speech", "recognized speech");
    }

    /**
     * @param id                   the provider identity reported by {@link #id()}
     * @param recognizedTranscript the transcript returned from {@link #recognize}
     */
    public FakeSuccessSpeechProvider(String id, String recognizedTranscript) {
        this.id = id;
        this.recognizedTranscript = recognizedTranscript;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
        return Optional.of(new RecognizedText(recognizedTranscript, language));
    }

    @Override
    public Optional<AudioClip> synthesize(String text, LanguageTag language) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return Optional.of(new AudioClip(bytes, SYNTHESIZED_MIME_TYPE));
    }
}
