package com.intentguard.assist;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.intentguard.llm.LlmProperties;
import com.intentguard.llm.OllamaClient;
import com.intentguard.llm.OllamaProperties;

/**
 * Configuration for the NL Operations Assistant package. Enables {@link AssistProperties} binding
 * and provides the {@link AssistTextGenerator} bean backed by either Google Gemini or Ollama,
 * depending on the {@code intentguard.llm.provider} property.
 */
@Configuration
@EnableConfigurationProperties(AssistProperties.class)
public class AssistConfig {

    private static final Logger log = System.getLogger(AssistConfig.class.getName());

    /**
     * Provides a system UTC clock bean for time-based operations. Can be overridden in tests.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Provides a Gemini-backed text generator for command generation. Lazily initializes the
     * Gemini {@code Client} and delegates to {@code models.generateContent}.
     */
    @Bean
    @ConditionalOnProperty(name = "intentguard.llm.provider", havingValue = "gemini", matchIfMissing = true)
    AssistTextGenerator geminiAssistTextGenerator(LlmProperties llmProperties) {
        return prompt -> {
            if (!llmProperties.hasApiKey()) {
                throw new IllegalStateException("No Gemini API key configured; command generation unavailable");
            }
            Client client = Client.builder().apiKey(llmProperties.getApiKey()).build();
            try {
                return client.models.generateContent(llmProperties.getModel(), prompt, null).text();
            } finally {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    log.log(Level.DEBUG, "Gemini client close failed (best-effort cleanup)");
                }
            }
        };
    }

    /**
     * Provides an Ollama-backed text generator for command generation. Calls the Ollama REST API
     * via the shared {@link OllamaClient}.
     */
    @Bean
    @ConditionalOnProperty(name = "intentguard.llm.provider", havingValue = "ollama")
    AssistTextGenerator ollamaAssistTextGenerator(OllamaProperties ollamaProperties) {
        OllamaClient client = new OllamaClient(ollamaProperties.getBaseUrl(),
                ollamaProperties.getApiKey(), ollamaProperties.getTimeoutMs());
        return prompt -> client.generate(ollamaProperties.getModel(), prompt,
                ollamaProperties.getTimeoutMs(), 0.3, 300)
                .orElseThrow(() -> new IllegalStateException(
                        "Ollama command generation returned empty"));
    }
}
