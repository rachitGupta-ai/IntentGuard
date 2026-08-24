package com.intentguard.speech;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link SpeechProperties} ({@code intentguard.speech.*}) so it can be injected into the
 * {@code SpeechService} and its provider adapters.
 */
@Configuration
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechConfig {
}
