package com.intentguard.speech;

import java.util.Arrays;
import java.util.Objects;

/**
 * A minimal value type wrapping a chunk of audio and its MIME type, exchanged with the
 * {@code Speech_Provider} for speech-to-text (STT) input and text-to-speech (TTS) output.
 *
 * <p>The clip carries only the raw bytes and a {@code mimeType} descriptor (for example
 * {@code "audio/wav"} or {@code "audio/mpeg"}); the language of the audio is tracked separately by
 * the {@link SpeechProvider} operations so that STT can enforce the preference-matching-language
 * rule (Req 4.5). The stored byte array is defensively copied on construction and access so the
 * clip is effectively immutable.
 *
 * @param data     the raw audio bytes (defensively copied; never {@code null})
 * @param mimeType the audio MIME type, for example {@code "audio/wav"} (non-blank)
 */
public record AudioClip(byte[] data, String mimeType) {

    public AudioClip {
        Objects.requireNonNull(data, "audio data must not be null");
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("audio mimeType must be non-blank");
        }
        data = data.clone();
    }

    /**
     * Convenience factory pairing raw bytes with a MIME type.
     *
     * @param data     the raw audio bytes
     * @param mimeType the audio MIME type
     * @return an immutable {@link AudioClip}
     */
    public static AudioClip of(byte[] data, String mimeType) {
        return new AudioClip(data, mimeType);
    }

    /**
     * @return a defensive copy of the raw audio bytes so callers cannot mutate the clip
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AudioClip that)) {
            return false;
        }
        return Arrays.equals(data, that.data) && mimeType.equals(that.mimeType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(data) + mimeType.hashCode();
    }

    @Override
    public String toString() {
        return "AudioClip[mimeType=" + mimeType + ", bytes=" + data.length + "]";
    }
}
