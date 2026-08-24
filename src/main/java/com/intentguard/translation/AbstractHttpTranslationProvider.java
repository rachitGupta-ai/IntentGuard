package com.intentguard.translation;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

/**
 * Shared base for the concrete {@link TranslationProvider} adapters
 * ({@link BhashiniTranslationProvider} and {@link CloudTranslationProvider}).
 *
 * <p>It centralizes the two guarantees the adapters must uphold:
 *
 * <ul>
 *   <li><strong>Encrypted transport only (Req 11.1).</strong> The provider endpoint is validated at
 *       construction to use the {@code https} scheme; a non-encrypted endpoint is rejected up front
 *       (a startup configuration error), so no adapter can ever transmit content in the clear.</li>
 *   <li><strong>Never throws across the boundary (Req 8.1).</strong> Following the
 *       {@code GeminiLlmService} pattern, each call runs on a bounded executor and is awaited with a
 *       per-call timeout; a missing API key, a timeout, or <em>any</em> runtime exception degrades
 *       to {@link Optional#empty()} rather than propagating, so the {@code TranslationService} can
 *       fall back deterministically to the original English content.</li>
 * </ul>
 *
 * <p>The concrete transport is created lazily and only when an API key is configured, so the Spring
 * context loads without credentials or network (degraded mode, Req 8.4).
 */
abstract class AbstractHttpTranslationProvider implements TranslationProvider {

    private static final Logger log =
            System.getLogger(AbstractHttpTranslationProvider.class.getName());

    protected final TranslationProperties properties;
    private final URI endpoint;
    private final ExecutorService executor;

    /** Injected transport (tests) or lazily-built HTTPS-backed transport (production). */
    private volatile TranslationHttpTransport transport;
    private volatile boolean transportInitAttempted;

    /**
     * @param properties     bound {@code intentguard.translation.*} configuration
     * @param defaultEndpoint the provider's HTTPS endpoint; rejected if not encrypted
     * @param transport      an injected transport for tests, or {@code null} to build lazily
     */
    AbstractHttpTranslationProvider(
            TranslationProperties properties, String defaultEndpoint, TranslationHttpTransport transport) {
        this.properties = properties;
        this.endpoint = requireEncrypted(defaultEndpoint);
        this.transport = transport;
        this.executor = Executors.newCachedThreadPool(daemonThreadFactory(id()));
    }

    /**
     * Validates that the endpoint uses an encrypted (HTTPS) transport (Req 11.1).
     *
     * @param rawEndpoint the endpoint URI string
     * @return the parsed {@link URI}
     * @throws IllegalArgumentException if the endpoint is blank, malformed, or not {@code https}
     */
    static URI requireEncrypted(String rawEndpoint) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            throw new IllegalArgumentException("Translation_Provider endpoint must be non-blank");
        }
        URI uri = URI.create(rawEndpoint.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            throw new IllegalArgumentException(
                    "Translation_Provider must use encrypted transport (https); got: " + rawEndpoint);
        }
        return uri;
    }

    /** The validated provider endpoint. */
    final URI endpoint() {
        return endpoint;
    }

    /** True when this adapter is configured to transmit only over encrypted transport (Req 11.1). */
    final boolean usesEncryptedTransport() {
        return "https".equalsIgnoreCase(endpoint.getScheme());
    }

    @Override
    public final Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        if (maskedText == null || source == null || target == null) {
            return Optional.empty();
        }
        TranslationHttpTransport active = resolveTransport();
        if (active == null) {
            return Optional.empty();
        }
        Future<String> future = executor.submit(() -> active.translate(maskedText, source, target));
        try {
            String result = future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(result).filter(text -> !text.isEmpty());
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.log(Level.DEBUG, "{0} translation exceeded the {1}ms budget; falling back to English",
                    id(), properties.getTimeoutMs());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            // Catch-all: any runtime exception maps to empty so the provider never throws across
            // the boundary (Req 8.1). The TranslationService then falls back to English.
            future.cancel(true);
            log.log(Level.DEBUG, "{0} translation failed; falling back to English", id());
            return Optional.empty();
        }
    }

    /**
     * Returns the active transport: the injected one if present, otherwise an HTTPS-backed
     * transport built lazily, or {@code null} when no API key is configured (degraded mode).
     */
    private TranslationHttpTransport resolveTransport() {
        TranslationHttpTransport injected = transport;
        if (injected != null) {
            return injected;
        }
        if (transportInitAttempted) {
            return transport;
        }
        synchronized (this) {
            if (transportInitAttempted) {
                return transport;
            }
            transportInitAttempted = true;
            if (!properties.hasApiKey()) {
                log.log(Level.INFO, "No translation API key configured; {0} runs in degraded mode "
                        + "(text translation disabled, content presented in English)", id());
                return null;
            }
            try {
                transport = buildTransport(endpoint, properties);
            } catch (RuntimeException constructionFailure) {
                log.log(Level.WARNING, "Could not construct {0} transport; running in degraded mode",
                        id(), constructionFailure);
                transport = null;
            }
            return transport;
        }
    }

    /**
     * Builds the concrete HTTPS-backed transport for this provider. Called at most once, lazily,
     * and only when an API key is present.
     *
     * @param endpoint   the validated HTTPS endpoint
     * @param properties the bound configuration (API key, timeout)
     * @return a transport performing the provider's encrypted network call
     */
    protected abstract TranslationHttpTransport buildTransport(URI endpoint, TranslationProperties properties);

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory(String providerId) {
        return runnable -> {
            Thread thread = new Thread(runnable, "translation-" + providerId);
            thread.setDaemon(true);
            return thread;
        };
    }
}
