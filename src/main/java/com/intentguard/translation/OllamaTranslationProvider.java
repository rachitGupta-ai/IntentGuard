package com.intentguard.translation;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.intentguard.llm.OllamaClient;
import com.intentguard.llm.OllamaProperties;

/**
 * Ollama-backed {@link TranslationProvider} adapter. Uses the same Ollama REST API as
 * {@link com.intentguard.llm.OllamaLlmService} but with translation-specific prompting.
 *
 * <p>Activated when {@code intentguard.ollama.base-url} is configured. The provider ID is
 * {@code "ollama"}, matching the {@code intentguard.translation.provider} config value.
 *
 * <p>Follows the project's never-throw convention: all failures degrade to
 * {@link Optional#empty()} so the {@code DefaultTranslationService} falls back to English.
 */
@Component
@ConditionalOnProperty(name = "intentguard.ollama.base-url")
public class OllamaTranslationProvider implements TranslationProvider {

    private static final Logger log = System.getLogger(OllamaTranslationProvider.class.getName());

    private final OllamaProperties properties;
    private final OllamaClient client;

    public OllamaTranslationProvider(OllamaProperties properties) {
        this.properties = properties;
        this.client = new OllamaClient(properties.getBaseUrl(), properties.getApiKey(),
                properties.getTimeoutMs());
        log.log(Level.INFO, "OllamaTranslationProvider activated — model={0}",
                properties.resolveTranslationModel());
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        if (maskedText == null) {
            return Optional.empty();
        }
        if (source.equals(target)) {
            return Optional.of(maskedText);
        }
        // Ollama-served models need a strongly-delimited prompt to avoid translating instructions.
        String prompt = OllamaTranslationPrompt.build(maskedText, source, target);
        return client.generate(properties.resolveTranslationModel(), prompt,
                properties.getTimeoutMs(), 0.1, 500)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }
}
