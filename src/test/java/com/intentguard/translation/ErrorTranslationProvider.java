package com.intentguard.translation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link TranslationProvider} test fake that simulates a provider error.
 *
 * <p>Mirroring the {@code LlmService} never-throw contract, it does not throw across the boundary;
 * instead every call returns {@link Optional#empty()}, driving the {@code TranslationService} to
 * present the original English content and record a {@code PROVIDER_ERROR} outcome (Req 2.5).
 */
public final class ErrorTranslationProvider implements TranslationProvider {

    private final String id;
    private final AtomicInteger invocations = new AtomicInteger();

    /** Creates an error fake with the default id {@code "error-fake"}. */
    public ErrorTranslationProvider() {
        this("error-fake");
    }

    /**
     * Creates an error fake with an explicit id.
     *
     * @param id the provider identity to report from {@link #id()}
     */
    public ErrorTranslationProvider(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        invocations.incrementAndGet();
        return Optional.empty();
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
