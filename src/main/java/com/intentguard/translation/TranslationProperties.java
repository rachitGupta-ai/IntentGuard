package com.intentguard.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code intentguard.translation.*} configuration (see {@code application.yml}) used by
 * the {@code TranslationService} and its {@code Translation_Provider} adapters (Req 8.1).
 *
 * <p>Mirrors {@link com.intentguard.llm.LlmProperties}: the API key is sourced from the
 * {@code TRANSLATION_API_KEY} environment variable and may be blank when no key is configured. A
 * blank key puts the feature in a degraded mode where text translation is disabled at startup and
 * Operator_Facing_Content is presented in English (Req 8.4), so the Spring context still loads
 * without network or credentials.
 */
@ConfigurationProperties(prefix = "intentguard.translation")
public class TranslationProperties {

    /** Translation_Provider identity: {@code bhashini}, {@code cloud}, or {@code offline}. */
    private String provider = "bhashini";

    /** Translation API key, supplied via {@code TRANSLATION_API_KEY}; blank disables live calls. */
    private String apiKey = "";

    /**
     * Per-call translation timeout in milliseconds. Bounds each Translation_Provider request so a
     * slow provider falls back to English within the 2-second budget (Req 2.4, 9.1).
     */
    private long timeoutMs = 2000;

    /**
     * Whether content marked sensitive may be transmitted to the Translation_Provider. When false,
     * sensitive content is presented in English and never sent to the provider (Req 11.3).
     */
    private boolean sensitiveContentTranslatable = false;

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

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isSensitiveContentTranslatable() {
        return sensitiveContentTranslatable;
    }

    public void setSensitiveContentTranslatable(boolean sensitiveContentTranslatable) {
        this.sensitiveContentTranslatable = sensitiveContentTranslatable;
    }

    /** True when a non-blank API key is configured; false enables degraded mode (Req 8.4). */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
