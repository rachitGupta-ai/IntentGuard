package com.intentguard.decision;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Minimal {@link ObjectProvider} test double wrapping a single instance, used to wire the stub
 * decision provider into the {@code InteractiveSignalIngestor} exactly as Spring would.
 */
final class SingletonObjectProvider<T> implements ObjectProvider<T> {

    private final T instance;

    private SingletonObjectProvider(T instance) {
        this.instance = instance;
    }

    static <T> SingletonObjectProvider<T> of(T instance) {
        return new SingletonObjectProvider<>(instance);
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
