package com.intentguard.translation;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link TranslationProvider} test fake that simulates a provider exceeding the
 * translation timeout budget (default 2 seconds).
 *
 * <p>On each call it sleeps for a configurable delay (default 3 seconds, deliberately beyond the
 * 2-second budget) before returning the masked text. When the {@code TranslationService} bounds the
 * call with its configured timeout, the service abandons this slow response and falls back to the
 * original English content, recording a {@code PROVIDER_TIMEOUT} outcome (Req 2.4, 9.1).
 *
 * <p>Honoring the {@link TranslationProvider} never-throw contract, an interruption during the
 * simulated delay is caught and mapped to {@link Optional#empty()} rather than propagated.
 */
public final class TimeoutTranslationProvider implements TranslationProvider {

    /** Default simulated delay, chosen to exceed the 2s translation budget. */
    public static final Duration DEFAULT_DELAY = Duration.ofMillis(3000);

    private final String id;
    private final Duration delay;
    private final AtomicInteger invocations = new AtomicInteger();

    /** Creates a timeout fake with the default id and a 3-second simulated delay. */
    public TimeoutTranslationProvider() {
        this("timeout-fake", DEFAULT_DELAY);
    }

    /**
     * Creates a timeout fake with an explicit simulated delay.
     *
     * @param delay how long each {@link #translate} call blocks before returning
     */
    public TimeoutTranslationProvider(Duration delay) {
        this("timeout-fake", delay);
    }

    /**
     * Fully-configurable constructor.
     *
     * @param id    the provider identity to report from {@link #id()}
     * @param delay how long each {@link #translate} call blocks before returning
     */
    public TimeoutTranslationProvider(String id, Duration delay) {
        this.id = id;
        this.delay = delay;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        invocations.incrementAndGet();
        try {
            Thread.sleep(Math.max(0L, delay.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        // Even if the service did not bound the call, a "late" success is still returned; a
        // timeout-bounded caller will already have fallen back to English before this arrives.
        return Optional.ofNullable(maskedText);
    }

    /**
     * The number of times {@link #translate} has been invoked on this fake.
     *
     * @return the invocation count
     */
    public int invocationCount() {
        return invocations.get();
    }
}
