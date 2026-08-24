package com.intentguard.persistence;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoException;

/**
 * Unit tests for {@link LastKnownGoodCache}, the resilient-read fallback used by the config and
 * profile repositories (Req 3.5, 4.5, 11.1, 11.2). No live Datastore is required.
 */
class LastKnownGoodCacheTest {

    @Test
    void cachesSuccessfulLoadAndReturnsIt() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();

        String value = cache.load("k", () -> "fresh");

        assertThat(value).isEqualTo("fresh");
        assertThat(cache.peek("k")).contains("fresh");
    }

    @Test
    void fallsBackToLastKnownGoodOnTransientReadFailure() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();
        cache.load("k", () -> "good"); // prime the cache with a successful read

        String value = cache.load("k", () -> {
            throw new MongoException("transient read failure");
        });

        assertThat(value).isEqualTo("good");
    }

    @Test
    void propagatesFailureWhenNoLastKnownGoodExists() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();

        assertThatThrownBy(() -> cache.load("k", () -> {
            throw new MongoException("transient read failure");
        })).isInstanceOf(MongoException.class);
    }

    @Test
    void freshSuccessOverwritesLastKnownGood() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();
        cache.load("k", () -> "v1");

        String second = cache.load("k", () -> "v2");
        assertThat(second).isEqualTo("v2");

        // A later transient failure now falls back to the most recent good value.
        String fallback = cache.load("k", () -> {
            throw new MongoException("boom");
        });
        assertThat(fallback).isEqualTo("v2");
    }

    @Test
    void nullLoadResultIsNotCachedAsGood() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();

        String result = cache.load("k", () -> null);
        assertThat(result).isNull();
        assertThat(cache.peek("k")).isEmpty();
    }

    @Test
    void putRecordsLastKnownGoodDirectly() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();
        cache.put("k", "written");

        String fallback = cache.load("k", () -> {
            throw new MongoException("boom");
        });
        assertThat(fallback).isEqualTo("written");
    }

    @Test
    void doesNotFallBackForNonMongoRuntimeExceptions() {
        LastKnownGoodCache<String, String> cache = new LastKnownGoodCache<>();
        cache.load("k", () -> "good");

        // Only transient Datastore (MongoException) failures fall back; programming errors surface.
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> cache.load("k", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }
}
