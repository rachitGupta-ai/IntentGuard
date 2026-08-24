package com.intentguard.speech;

import java.util.Optional;

import com.intentguard.translation.LanguageTag;

/**
 * In-memory {@link SpeechProvider} fake that simulates a hanging provider, for use as test support
 * by later Speech_Service tasks exercising the STT (10s) and TTS (5s) timeout paths (Req 4.3, 5.3).
 *
 * <p>Each operation sleeps for a configured delay (default 60s — far beyond any speech timeout
 * budget) before it would otherwise return, so a timeout-bounded caller aborts the call first.
 * Following the {@code LlmService} never-throw contract, an interruption is swallowed and mapped to
 * {@link Optional#empty()} rather than propagated.
 */
public final class FakeTimeoutSpeechProvider implements SpeechProvider {

    /** Default hang duration, chosen to exceed the STT and TTS timeout budgets. */
    public static final long DEFAULT_DELAY_MS = 60_000L;

    private final String id;
    private final long delayMs;

    /**
     * Creates a fake with the default identity and a 60s hang.
     */
    public FakeTimeoutSpeechProvider() {
        this("fake-timeout-speech", DEFAULT_DELAY_MS);
    }

    /**
     * @param id      the provider identity reported by {@link #id()}
     * @param delayMs how long each operation sleeps before returning
     */
    public FakeTimeoutSpeechProvider(String id, long delayMs) {
        this.id = id;
        this.delayMs = delayMs;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<RecognizedText> recognize(AudioClip audio, LanguageTag language) {
        return hangThenEmpty();
    }

    @Override
    public Optional<AudioClip> synthesize(String text, LanguageTag language) {
        return hangThenEmpty();
    }

    private <T> Optional<T> hangThenEmpty() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
