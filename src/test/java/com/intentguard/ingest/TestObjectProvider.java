package com.intentguard.ingest;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Minimal {@link ObjectProvider} test double wrapping a single, possibly-{@code null} instance.
 * Lets the ingestor be unit-tested with (or without) an injected decision provider.
 */
final class TestObjectProvider<T> implements ObjectProvider<T> {

    private final T instance;

    private TestObjectProvider(T instance) {
        this.instance = instance;
    }

    static <T> TestObjectProvider<T> of(T instance) {
        return new TestObjectProvider<>(instance);
    }

    static <T> TestObjectProvider<T> empty() {
        return new TestObjectProvider<>(null);
    }

    @Override
    public T getObject() {
        if (instance == null) {
            throw new NoSuchBeanDefinitionException("no instance available");
        }
        return instance;
    }

    @Override
    public T getObject(Object... args) {
        return getObject();
    }

    @Override
    public T getIfAvailable() {
        return instance;
    }

    @Override
    public T getIfUnique() {
        return instance;
    }
}
