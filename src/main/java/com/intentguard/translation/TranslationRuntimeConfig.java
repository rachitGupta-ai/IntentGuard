package com.intentguard.translation;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.intentguard.speech.SpeechProperties;

import jakarta.annotation.PostConstruct;

/**
 * Holds the active Translation_Provider / Speech_Provider runtime configuration in memory and
 * applies Administrator updates without a restart (hot-reload; Req 8.3).
 *
 * <p>This follows the same shape as
 * {@link com.intentguard.config.ThresholdConfigurationService}: the active configuration is served
 * from an {@link AtomicReference} so that subsequent translation, recognition, and synthesis
 * requests read the newest applied configuration with no restart. An
 * {@link #applyUpdate(TranslationRuntimeUpdate) update} is validated by materializing a
 * {@link Snapshot} (valid by construction); on success the reference is swapped and on failure an
 * {@link IllegalArgumentException} is thrown and the previously active snapshot is retained
 * unchanged. The persist-then-swap analogue here is a pure in-memory swap guarded by a monitor so
 * concurrent updates stay consistent.
 *
 * <p>Credential <strong>presence</strong> is evaluated only at startup (Req 8.6): the
 * {@link #isTextTranslationEnabled()} and {@link #isSpeechEnabled()} capability flags are computed
 * once in {@link #initialize()} from {@link TranslationProperties#hasApiKey()} and
 * {@link SpeechProperties#hasApiKey()} and are never recomputed. A runtime update can change which
 * provider is used and its timeouts, but it can never re-enable a capability whose credential was
 * absent at startup. The finer-grained gating (missing translation credential disables only text
 * translation; missing speech credential disables only speech; Req 8.4, 8.5) is layered on these
 * startup-fixed flags by the controller surface.
 */
@Service
public class TranslationRuntimeConfig {

    /**
     * An immutable snapshot of the runtime provider configuration. Valid by construction: provider
     * identities are non-blank and timeouts are strictly positive.
     *
     * @param translationProviderId        the active Translation_Provider identity
     * @param translationTimeoutMs         the per-call translation timeout in milliseconds
     * @param sensitiveContentTranslatable whether sensitive content may be sent to the provider
     * @param speechProviderId             the active Speech_Provider identity
     * @param sttTimeoutMs                 the speech-to-text timeout in milliseconds
     * @param ttsTimeoutMs                 the text-to-speech timeout in milliseconds
     */
    public record Snapshot(
            String translationProviderId,
            long translationTimeoutMs,
            boolean sensitiveContentTranslatable,
            String speechProviderId,
            long sttTimeoutMs,
            long ttsTimeoutMs) {

        public Snapshot {
            if (translationProviderId == null || translationProviderId.isBlank()) {
                throw new IllegalArgumentException("translationProviderId must be non-blank");
            }
            if (speechProviderId == null || speechProviderId.isBlank()) {
                throw new IllegalArgumentException("speechProviderId must be non-blank");
            }
            if (translationTimeoutMs <= 0) {
                throw new IllegalArgumentException("translationTimeoutMs must be positive");
            }
            if (sttTimeoutMs <= 0) {
                throw new IllegalArgumentException("sttTimeoutMs must be positive");
            }
            if (ttsTimeoutMs <= 0) {
                throw new IllegalArgumentException("ttsTimeoutMs must be positive");
            }
            translationProviderId = translationProviderId.trim();
            speechProviderId = speechProviderId.trim();
        }
    }

    private final TranslationProperties translationProperties;
    private final SpeechProperties speechProperties;
    private final AtomicReference<Snapshot> active = new AtomicReference<>();
    private final Object updateLock = new Object();

    // Credential presence is evaluated ONCE at startup (Req 8.6); these flags never change
    // afterward, regardless of any runtime provider update.
    private volatile boolean textTranslationEnabled;
    private volatile boolean speechEnabled;

    public TranslationRuntimeConfig(TranslationProperties translationProperties,
            SpeechProperties speechProperties) {
        this.translationProperties = translationProperties;
        this.speechProperties = speechProperties;
    }

    /**
     * Seeds the active snapshot from the bound properties and evaluates credential presence exactly
     * once (Req 8.6). Invoked by Spring after construction.
     */
    @PostConstruct
    public void initialize() {
        // Text translation is enabled when a Translation_Provider credential is present, OR when the
        // active provider is the Ollama backend (which authenticates via intentguard.ollama.* and
        // needs no Gemini-style translation key). The Ollama provider degrades gracefully to an
        // English fallback if its endpoint is unreachable, so enabling the capability is safe.
        boolean ollamaProvider = "ollama".equalsIgnoreCase(translationProperties.getProvider());
        this.textTranslationEnabled = translationProperties.hasApiKey() || ollamaProvider;
        this.speechEnabled = speechProperties.hasApiKey();
        active.set(new Snapshot(
                translationProperties.getProvider(),
                translationProperties.getTimeoutMs(),
                translationProperties.isSensitiveContentTranslatable(),
                speechProperties.getProvider(),
                speechProperties.getSttTimeoutMs(),
                speechProperties.getTtsTimeoutMs()));
    }

    /** The runtime provider configuration currently in effect. */
    public Snapshot getActive() {
        return active.get();
    }

    /**
     * Validates and applies an Administrator update, merging its non-null fields onto the active
     * snapshot. On success the new configuration takes effect for subsequent requests immediately
     * (no restart; Req 8.3).
     *
     * @param update the fields to change; {@code null} fields retain the current value
     * @return the newly active snapshot
     * @throws IllegalArgumentException if the merged configuration is invalid; the previously
     *                                  active snapshot is retained unchanged
     */
    public Snapshot applyUpdate(TranslationRuntimeUpdate update) {
        synchronized (updateLock) {
            Snapshot current = active.get();
            // Materializing the candidate validates it; an invalid update throws here, before the
            // reference swap, so the previous snapshot is retained.
            Snapshot candidate = new Snapshot(
                    update.translationProviderId() != null
                            ? update.translationProviderId() : current.translationProviderId(),
                    update.translationTimeoutMs() != null
                            ? update.translationTimeoutMs() : current.translationTimeoutMs(),
                    update.sensitiveContentTranslatable() != null
                            ? update.sensitiveContentTranslatable() : current.sensitiveContentTranslatable(),
                    update.speechProviderId() != null
                            ? update.speechProviderId() : current.speechProviderId(),
                    update.sttTimeoutMs() != null
                            ? update.sttTimeoutMs() : current.sttTimeoutMs(),
                    update.ttsTimeoutMs() != null
                            ? update.ttsTimeoutMs() : current.ttsTimeoutMs());
            active.set(candidate);
            return candidate;
        }
    }

    /**
     * Whether text translation is enabled, evaluated only at startup from the presence of a
     * Translation_Provider credential (Req 8.4, 8.6). When false, Operator_Facing_Content is
     * presented in English.
     */
    public boolean isTextTranslationEnabled() {
        return textTranslationEnabled;
    }

    /**
     * Whether speech (STT/TTS) is enabled, evaluated only at startup from the presence of a
     * Speech_Provider credential (Req 8.5, 8.6).
     */
    public boolean isSpeechEnabled() {
        return speechEnabled;
    }
}
