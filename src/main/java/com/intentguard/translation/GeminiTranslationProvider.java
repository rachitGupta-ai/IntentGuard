package com.intentguard.translation;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.genai.Client;
import com.intentguard.llm.LlmProperties;

import jakarta.annotation.PreDestroy;

/**
 * Gemini-backed {@link TranslationProvider} adapter (Req 1.1, 1.2).
 *
 * <p>Uses the Google Gemini Generate API ({@code models.generateContent}) for text-to-text
 * translation. Mirrors the resilience patterns of {@code GeminiLlmService}: lazy {@link Client}
 * construction, daemon-thread executor, per-call timeout via {@code Future.get()}, and a
 * never-throw boundary that degrades to {@link Optional#empty()} on any failure.
 *
 * <p><strong>API key resolution (Req 6.1, 6.3):</strong> If a dedicated
 * {@code intentguard.translation.api-key} is configured (non-blank), it takes priority. Otherwise
 * the provider falls back to {@code intentguard.llm.api-key} (bound from {@code GEMINI_API_KEY}).
 *
 * <p><strong>Degraded mode (Req 11.2):</strong> When the resolved key is blank or client
 * construction fails, all {@link #translate} calls return {@link Optional#empty()} without network
 * I/O.
 */
@Component
public class GeminiTranslationProvider implements TranslationProvider {

    private static final Logger log = System.getLogger(GeminiTranslationProvider.class.getName());

    private final LlmProperties llmProperties;
    private final TranslationProperties translationProperties;
    private final ExecutorService executor;

    /** Injected generator (tests) or lazily-built SDK-backed generator (production). */
    private volatile GeminiTextGenerator generator;
    private volatile boolean clientInitAttempted;
    private volatile Client client;

    @Autowired
    public GeminiTranslationProvider(LlmProperties llmProperties,
                                     TranslationProperties translationProperties) {
        this(llmProperties, translationProperties, null);
    }

    /**
     * Test seam: supply a {@link GeminiTextGenerator} directly to exercise timeout, parsing, and
     * fallback behavior without the SDK. When {@code generator} is {@code null}, an SDK-backed
     * generator is built lazily from the configured client.
     */
    GeminiTranslationProvider(LlmProperties llmProperties,
                              TranslationProperties translationProperties,
                              GeminiTextGenerator generator) {
        this.llmProperties = llmProperties;
        this.translationProperties = translationProperties;
        this.generator = generator;
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory());
    }

    @Override
    public String id() {
        return "gemini";
    }

    /**
     * Translates masked text from {@code source} into {@code target} via the Gemini Generate API.
     *
     * <p>Returns {@link Optional#empty()} when the input is null, no generator is available
     * (degraded mode), the call times out, or any other error occurs. Never throws.
     */
    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        if (maskedText == null) {
            return Optional.empty();
        }
        if (source.equals(target)) {
            return Optional.of(maskedText);
        }
        GeminiTextGenerator active = resolveGenerator();
        if (active == null) {
            return Optional.empty();
        }
        String prompt = GeminiTranslationPrompt.build(maskedText, source, target);
        Future<String> future = executor.submit(() -> active.generate(prompt));
        try {
            String result = future.get(translationProperties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null || result.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(result.trim());
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini translation call exceeded the {0}ms budget; returning empty",
                    translationProperties.getTimeoutMs());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini translation call failed; returning empty", error);
            return Optional.empty();
        }
    }

    /**
     * Returns the active generator: the injected one if present, otherwise an SDK-backed generator
     * built lazily from the configured client, or {@code null} when no client is available.
     */
    GeminiTextGenerator resolveGenerator() {
        GeminiTextGenerator injected = generator;
        if (injected != null) {
            return injected;
        }
        Client c = clientOrNull();
        if (c == null) {
            return null;
        }
        GeminiTextGenerator built = prompt ->
                c.models.generateContent(llmProperties.getModel(), prompt, null).text();
        generator = built;
        return built;
    }

    /**
     * Lazily builds the Gemini {@code Client} exactly once. Returns {@code null} when no API key is
     * configured or client construction fails, so callers degrade gracefully (Req 8.1, 11.2).
     */
    private Client clientOrNull() {
        Client existing = client;
        if (existing != null || clientInitAttempted) {
            return existing;
        }
        synchronized (this) {
            if (clientInitAttempted) {
                return client;
            }
            clientInitAttempted = true;
            String apiKey = resolveApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.log(Level.INFO, "No Gemini API key configured for translation; "
                        + "GeminiTranslationProvider runs in degraded mode (all translate calls "
                        + "return empty)");
                return null;
            }
            try {
                client = Client.builder().apiKey(apiKey).build();
            } catch (RuntimeException constructionFailure) {
                log.log(Level.WARNING, "Could not construct the Gemini client for translation; "
                        + "GeminiTranslationProvider runs in degraded mode", constructionFailure);
                client = null;
            }
            return client;
        }
    }

    /**
     * Resolves the API key for translation: dedicated {@code intentguard.translation.api-key} takes
     * priority; if blank, falls back to {@code intentguard.llm.api-key} (Req 6.1, 6.3).
     */
    String resolveApiKey() {
        if (translationProperties.hasApiKey()) {
            return translationProperties.getApiKey();
        }
        return llmProperties.getApiKey();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns the executor for use by the translate method (task 2.2 will use this).
     */
    ExecutorService executor() {
        return executor;
    }

    /**
     * Returns the translation properties for timeout configuration access.
     */
    TranslationProperties translationProperties() {
        return translationProperties;
    }

    /**
     * Returns the LLM properties for model name access.
     */
    LlmProperties llmProperties() {
        return llmProperties;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "translation-gemini");
            thread.setDaemon(true);
            return thread;
        };
    }
}
