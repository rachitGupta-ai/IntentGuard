package com.intentguard.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link LlmProperties} ({@code intentguard.llm.*}) so it can be injected into
 * {@link GeminiLlmService}.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, OllamaProperties.class})
public class LlmConfig {
}
