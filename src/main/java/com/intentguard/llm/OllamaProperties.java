package com.intentguard.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code intentguard.ollama.*} configuration used by the Ollama-backed LLM providers.
 *
 * <p>When an Ollama base URL is configured and reachable, the engine uses it for semantic scoring,
 * explanations, translation, and NL command generation — replacing the Gemini SDK path entirely.
 * This allows on-premise or BT-server deployments where no external API key is required.
 */
@ConfigurationProperties(prefix = "intentguard.ollama")
public class OllamaProperties {

    /** Base URL of the Ollama-compatible server (e.g., {@code http://localhost:11434}). */
    private String baseUrl = "";

    /** Model to use for scoring and explanations. */
    private String model = "gemma4:12b";

    /** Model to use for translation (may differ from the scoring model). */
    private String translationModel = "";

    /** Optional API key sent as {@code x-api-key} header (required for BT server, blank for local Ollama). */
    private String apiKey = "";

    /**
     * Per-call timeout in milliseconds for non-blocking paths (translation, NL command generation).
     * These are not on the synchronous shell-hook gate, so a longer budget is acceptable.
     */
    private long timeoutMs = 30000;

    /**
     * Per-call timeout in milliseconds for the synchronous scoring path (Semantic_Inconsistency on
     * the shell hook). Kept tight so a slow/loaded LLM server causes the semantic component to be
     * EXCLUDED and the deterministic components still score and block within the decision budget —
     * the graceful-degradation guarantee. Defaults to {@link #timeoutMs} when not set.
     */
    private long scoringTimeoutMs = 8000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTranslationModel() {
        return translationModel;
    }

    public void setTranslationModel(String translationModel) {
        this.translationModel = translationModel;
    }

    /** Returns the model to use for translation; falls back to the main model if not set. */
    public String resolveTranslationModel() {
        return (translationModel != null && !translationModel.isBlank()) ? translationModel : model;
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

    public long getScoringTimeoutMs() {
        return scoringTimeoutMs;
    }

    public void setScoringTimeoutMs(long scoringTimeoutMs) {
        this.scoringTimeoutMs = scoringTimeoutMs;
    }

    /** True when a non-blank base URL is configured. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
