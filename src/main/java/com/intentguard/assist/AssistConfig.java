package com.intentguard.assist;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.intentguard.llm.LlmProperties;

/**
 * Configuration for the NL Operations Assistant package. Enables {@link AssistProperties} binding
 * and provides the {@link AssistTextGenerator} bean backed by the Google Gemini SDK.
 *
 * <p>The bean reuses the API key and model from {@link LlmProperties} (the existing
 * {@code intentguard.llm.*} namespace). When no API key is configured, the generator throws on
 * every call so that callers wrap the failure as {@link AssistGenerationException}.
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
    AssistTextGenerator assistTextGenerator(LlmProperties llmProperties) {
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
}
