package com.intentguard.translation;

/**
 * An Administrator-supplied update to the runtime Translation_Provider / Speech_Provider
 * configuration held by {@link TranslationRuntimeConfig} (Req 8.3).
 *
 * <p>Every field is nullable: a {@code null} value means "leave the currently active value
 * unchanged", so an Administrator can, for example, swap only the Translation_Provider identity
 * without restating the timeouts. {@link TranslationRuntimeConfig#applyUpdate} validates and merges
 * the non-null fields onto the active snapshot.
 *
 * <p>Credential presence is deliberately <strong>not</strong> part of this update: whether text
 * translation and speech are enabled is evaluated only at startup (Req 8.6) and cannot be toggled
 * by a runtime update.
 *
 * @param translationProviderId        new Translation_Provider identity, or {@code null} to keep
 * @param translationTimeoutMs         new per-call translation timeout, or {@code null} to keep
 * @param sensitiveContentTranslatable new sensitive-content policy, or {@code null} to keep
 * @param speechProviderId             new Speech_Provider identity, or {@code null} to keep
 * @param sttTimeoutMs                 new speech-to-text timeout, or {@code null} to keep
 * @param ttsTimeoutMs                 new text-to-speech timeout, or {@code null} to keep
 */
public record TranslationRuntimeUpdate(
        String translationProviderId,
        Long translationTimeoutMs,
        Boolean sensitiveContentTranslatable,
        String speechProviderId,
        Long sttTimeoutMs,
        Long ttsTimeoutMs) {
}
