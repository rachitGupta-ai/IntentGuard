package com.intentguard.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code intentguard.speech.*} configuration (see {@code application.yml}) used by the
 * {@code SpeechService} and its {@code Speech_Provider} adapters (Req 8.2).
 *
 * <p>Mirrors {@link com.intentguard.llm.LlmProperties}: the API key is sourced from the
 * {@code SPEECH_API_KEY} environment variable and may be blank when no key is configured. A blank
 * key puts the feature in a degraded mode where speech is disabled at startup (Req 8.5), so the
 * Spring context still loads without network or credentials.
 */
@ConfigurationProperties(prefix = "intentguard.speech")
public class SpeechProperties {

    /** Speech_Provider identity: {@code bhashini} or {@code cloud}. */
    private String provider = "bhashini";

    /** Speech API key, supplied via {@code SPEECH_API_KEY}; blank disables live calls. */
    private String apiKey = "";

    /**
     * Speech-to-text (STT) timeout in milliseconds. On elapse the audio is discarded and the
     * Operator is prompted to retry (Req 4.3).
     */
    private long sttTimeoutMs = 10000;

    /**
     * Text-to-speech (TTS) timeout in milliseconds. On elapse the content is presented as text and
     * playback is recorded as unavailable (Req 5.3).
     */
    private long ttsTimeoutMs = 5000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getSttTimeoutMs() {
        return sttTimeoutMs;
    }

    public void setSttTimeoutMs(long sttTimeoutMs) {
        this.sttTimeoutMs = sttTimeoutMs;
    }

    public long getTtsTimeoutMs() {
        return ttsTimeoutMs;
    }

    public void setTtsTimeoutMs(long ttsTimeoutMs) {
        this.ttsTimeoutMs = ttsTimeoutMs;
    }

    /** True when a non-blank API key is configured; false enables degraded mode (Req 8.5). */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
