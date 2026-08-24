package com.intentguard.translation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link TranslationProperties} ({@code intentguard.translation.*}) so it can be injected
 * into the {@code TranslationService} and its provider adapters, and exposes the default
 * {@link SupportedLanguages} set as a bean for the {@code LanguagePreferenceService} and the
 * unsupported-language guard.
 */
@Configuration
@EnableConfigurationProperties(TranslationProperties.class)
public class TranslationConfig {

    /**
     * The default {@code Supported_Language} set (Req 6.2), the single source of truth for the
     * membership check used by preference selection (Req 1.5) and the unsupported-language guard.
     *
     * @return the default {@link SupportedLanguages} holder
     */
    @Bean
    public SupportedLanguages supportedLanguages() {
        return SupportedLanguages.defaults();
    }

    /**
     * The Technical_Token protector shared by the {@code TranslationService} (and later the
     * {@code SpeechService}) to mask/restore command text, paths, identifiers, scores, timestamps,
     * and reason codes byte-for-byte across provider calls (Req 2.3, 5.2, 7.1, 7.4).
     *
     * @return a stateless, thread-safe {@link TechnicalTokenProtector}
     */
    @Bean
    public TechnicalTokenProtector technicalTokenProtector() {
        return new TechnicalTokenProtector();
    }

    /**
     * The domain glossary applied by the {@code TranslationService} so configured security terms are
     * rendered as their operator-approved Translated_Text per target language (Req 12.2, stretch).
     *
     * <p>Defaults to {@link DomainGlossary#empty()} (no terms configured), under which translation
     * behaves exactly as before. A deployment that wants glossary enforcement can override this bean
     * with a populated {@link DomainGlossary#of(java.util.Map)}; nothing else in the flow changes.
     *
     * @return the configured {@link DomainGlossary}, empty by default
     */
    @Bean
    public DomainGlossary domainGlossary() {
        return DomainGlossary.empty();
    }
}
