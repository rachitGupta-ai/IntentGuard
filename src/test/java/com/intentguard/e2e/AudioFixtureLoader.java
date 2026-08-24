package com.intentguard.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import com.intentguard.speech.AudioClip;
import com.intentguard.speech.GeminiSpeechProvider;
import com.intentguard.translation.LanguageTag;

/**
 * Loads pre-recorded audio fixtures or falls back to Gemini TTS synthesis.
 *
 * <p>Audio fixtures follow the naming convention
 * {@code e2e/audio/{languageTag}_{commandIndex zero-padded to 3 digits}.wav} on the classpath.
 * When a pre-recorded file is not available, the loader delegates to
 * {@link GeminiSpeechProvider#synthesize(String, LanguageTag)} as a fallback.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.4, 2.5
 */
final class AudioFixtureLoader {

    private AudioFixtureLoader() {
        // utility class — no instantiation
    }

    /**
     * Loads an audio fixture from the classpath or synthesizes one via TTS.
     *
     * @param languageTag    BCP-47 language tag (e.g. "hi", "ta")
     * @param commandIndex   1-based command index from the corpus
     * @param commandText    the command text to synthesize if no fixture exists
     * @param speechProvider the Gemini speech provider for TTS fallback
     * @return an {@link AudioClip} with the audio data
     * @throws IllegalStateException if neither a fixture file nor TTS synthesis produces audio
     */
    static AudioClip loadOrSynthesize(String languageTag, int commandIndex,
                                      String commandText, GeminiSpeechProvider speechProvider) {
        String resourcePath = String.format("e2e/audio/%s_%03d.wav", languageTag, commandIndex);

        // Attempt to load pre-recorded fixture from classpath
        try (InputStream is = AudioFixtureLoader.class.getResourceAsStream("/" + resourcePath)) {
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                return AudioClip.of(bytes, "audio/wav");
            }
        } catch (IOException e) {
            // Fall through to TTS synthesis
        }

        // Fallback: synthesize via Gemini TTS
        Optional<AudioClip> synthesized = speechProvider.synthesize(commandText, LanguageTag.of(languageTag));
        return synthesized.orElseThrow(() -> new IllegalStateException(
                String.format("Audio fixture not found and TTS synthesis failed — "
                        + "language: %s, commandIndex: %d, commandText: '%s', "
                        + "resource path: %s",
                        languageTag, commandIndex, commandText, resourcePath)));
    }
}
