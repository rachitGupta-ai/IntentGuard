package com.intentguard.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code intentguard.llm.*} configuration (see {@code application.yml}) used by
 * {@link GeminiLlmService}.
 *
 * <p>The API key is sourced from the {@code GEMINI_API_KEY} environment variable and may be blank
 * when no key is configured; in that case the adapter runs in a degraded mode (all calls return
 * empty) rather than failing, so the Spring context still loads without network or credentials.
 */
@ConfigurationProperties(prefix = "intentguard.llm")
public class LlmProperties {

    /** Gemini API key, supplied via {@code GEMINI_API_KEY}; blank disables live calls. */
    private String apiKey = "";

    /** Gemini model identifier. Default matches application.yml. */
    private String model = "gemini-2.5-flash";

    /**
     * Per-call timeout in milliseconds. Must stay tighter than the 2-second decision budget so a
     * slow LLM leaves headroom to finish the composite from the remaining components.
     */
    private long timeoutMs = 1200;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** True when a non-blank API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
