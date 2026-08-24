package com.intentguard.translation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link TranslationProvider} test fake that succeeds by returning the masked text
 * unchanged (a deterministic, sentinel-preserving "translation").
 *
 * <p>Because it never alters the sentinels inserted by the {@code TechnicalTokenProtector}, a
 * restore over its output reproduces every Technical_Token byte-for-byte. It is the baseline
 * success provider used by property and unit tests that need a working translation without a
 * network call.
 *
 * <p>The provider records how many times {@link #translate} was invoked via
 * {@link #invocationCount()} so tests can assert caching / passthrough behavior (for example that
 * no provider request is issued for English passthrough or a cache hit).
 */
public final class PassthroughTranslationProvider implements TranslationProvider {

    private final String id;
    private final AtomicInteger invocations = new AtomicInteger();

    /** Creates a passthrough fake with the default id {@code "passthrough-fake"}. */
    public PassthroughTranslationProvider() {
        this("passthrough-fake");
    }

    /**
     * Creates a passthrough fake with an explicit id.
     *
     * @param id the provider identity to report from {@link #id()}
     */
    public PassthroughTranslationProvider(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> translate(String maskedText, LanguageTag source, LanguageTag target) {
        invocations.incrementAndGet();
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
