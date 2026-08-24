package com.intentguard.llm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.genai.Client;
import com.intentguard.domain.CommandEvent;
import com.intentguard.domain.Decision;
import com.intentguard.domain.DivergenceResult;

import jakarta.annotation.PreDestroy;

/**
 * Gemini-backed {@link LlmService} adapter (Req 6). It wraps the Google Gemini Java SDK for two
 * best-effort uses — Semantic_Inconsistency scoring and Explanation text — with a per-call timeout
 * tighter than the 2-second decision budget.
 *
 * <p><strong>Resilience.</strong> The Gemini {@code Client} is created lazily on first use and only
 * when an API key is configured. A missing key, a client-construction failure, a call error, or a
 * timeout all degrade to an empty result rather than an exception, so the Spring context loads and
 * the scoring pipeline keeps working (with the semantic component excluded) even with no key and no
 * network.
 *
 * <p><strong>Timeout.</strong> Each call runs on a bounded executor and is awaited with
 * {@link Future#get(long, TimeUnit)} using the configured {@code timeoutMs}; on timeout the task is
 * cancelled and an empty result is returned (Req 6.3).
 */
@Service
public class GeminiLlmService implements LlmService {

    private static final Logger log = System.getLogger(GeminiLlmService.class.getName());

    private final LlmProperties properties;
    private final ExecutorService executor;

    /** Injected generator (tests) or lazily-built SDK-backed generator (production). */
    private volatile GeminiTextGenerator generator;
    private volatile boolean clientInitAttempted;
    private volatile Client client;

    @Autowired
    public GeminiLlmService(LlmProperties properties) {
        this(properties, null);
    }

    /**
     * Test seam: supply a {@link GeminiTextGenerator} directly to exercise timeout/parse/fallback
     * behavior without the SDK. When {@code generator} is {@code null}, an SDK-backed generator is
     * built lazily from {@code properties}.
     */
    GeminiLlmService(LlmProperties properties, GeminiTextGenerator generator) {
        this.properties = properties;
        this.generator = generator;
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory());
    }

    @Override
    public OptionalDouble semanticInconsistency(CommandEvent event, String intentText) {
        if (event == null || intentText == null || intentText.isBlank()) {
            return OptionalDouble.empty();
        }
        String prompt = LlmPromptBuilder.semanticPrompt(event, intentText);
        Optional<String> response = callWithTimeout(prompt);
        if (response.isEmpty()) {
            return OptionalDouble.empty();
        }
        // Malformed model output is treated as an error and clamps are applied to in-range values.
        return LlmResponseParser.parseSemanticScore(response.get());
    }

    @Override
    public Optional<String> summarizeIntent(java.util.List<String> recentCommands) {
        if (recentCommands == null || recentCommands.isEmpty()) {
            return Optional.empty();
        }
        String prompt = LlmPromptBuilder.summarizeIntentPrompt(recentCommands);
        return callWithTimeout(prompt)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }

    @Override
    public Optional<String> explain(CommandEvent event, DivergenceResult result, Decision decision) {
        if (event == null || result == null || decision == null) {
            return Optional.empty();
        }
        String prompt = LlmPromptBuilder.explanationPrompt(event, result, decision);
        return callWithTimeout(prompt)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }

    /**
     * Runs a single generation bounded by the configured timeout. Returns empty on unavailable
     * generator (no key / construction failure), timeout, or any call error.
     */
    private Optional<String> callWithTimeout(String prompt) {
        GeminiTextGenerator active = resolveGenerator();
        if (active == null) {
            return Optional.empty();
        }
        Future<String> future = executor.submit(() -> active.generate(prompt));
        try {
            return Optional.ofNullable(future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini call exceeded the {0}ms budget; excluding result",
                    properties.getTimeoutMs());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            future.cancel(true);
            log.log(Level.DEBUG, "Gemini call failed; excluding result", error);
            return Optional.empty();
        }
    }

    /**
     * Returns the active generator: the injected one if present, otherwise an SDK-backed generator
     * built lazily from the configured client, or {@code null} when no client is available.
     */
    private GeminiTextGenerator resolveGenerator() {
        GeminiTextGenerator injected = generator;
        if (injected != null) {
            return injected;
        }
        Client c = clientOrNull();
        if (c == null) {
            return null;
        }
        GeminiTextGenerator built = prompt -> c.models.generateContent(properties.getModel(), prompt, null).text();
        generator = built;
        return built;
    }

    /**
     * Lazily builds the Gemini {@code Client} exactly once. Returns {@code null} when no API key is
     * configured or client construction fails, so callers degrade gracefully.
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
            if (!properties.hasApiKey()) {
                log.log(Level.INFO, "No Gemini API key configured; LLM_Service runs in degraded "
                        + "mode (semantic scoring and LLM explanations disabled)");
                return null;
            }
            try {
                client = Client.builder().apiKey(properties.getApiKey()).build();
            } catch (RuntimeException constructionFailure) {
                log.log(Level.WARNING, "Could not construct the Gemini client; LLM_Service runs in "
                        + "degraded mode", constructionFailure);
                client = null;
            }
            return client;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        Client c = client;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "gemini-llm");
            thread.setDaemon(true);
            return thread;
        };
    }
}
