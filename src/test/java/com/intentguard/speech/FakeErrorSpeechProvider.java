package com.intentguard.speech;

import java.util.Optional;

import com.intentguard.translation.LanguageTag;

/**
 * In-memory {@link SpeechProvider} fake that simulates a provider error, for use as test support by
 * later Speech_Service tasks exercising the STT error (Req 4.4) and TTS error (Req 5.4) paths.
 *
 * <p>Both operations return {@link Optional#empty()} promptly, modelling a provider that fails
 * within the timeout budget. Following the {@code LlmService} never-throw contract the error is
 * expressed as an empty result rather than a thrown exception, so the {@code SpeechService} degrades
 * gracefully.
 */
public final class FakeErrorSpeechProvider implements SpeechProvider {

    private final String id;

    /**
     * Creates a fake with the default identity.
     */
    public FakeErrorSpeechProvider() {
        this("fake-error-speech");
    }

    /**
     * @param id the provider identity reported by {@link #id()}
     */
    public FakeErrorSpeechProvider(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
        return Optional.empty();
    }

    @Override
    public Optional<AudioClip> synthesize(String text, LanguageTag language) {
        return Optional.empty();
    }
}
