package com.intentguard.persistence;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.mongodb.MongoException;

/**
 * A small in-memory cache that keeps the last successfully read value per key and serves it as a
 * fallback when a subsequent read fails transiently (Req 3.5, 4.5, 11.1, 11.2 — resilient reads of
 * config and profiles).
 *
 * <p>On {@link #load(Object, Supplier)}:
 * <ul>
 *   <li>the loader is invoked; on success the (non-null) value is cached and returned;</li>
 *   <li>if the loader throws a {@link MongoException} (a transient Datastore read failure) and a
 *       last-known-good value exists for the key, that cached value is returned instead;</li>
 *   <li>if no cached value exists, the failure propagates so the caller can react.</li>
 * </ul>
 *
 * <p>The cache is deliberately unbounded and simple: the key spaces here (per-user profiles and a
 * single active configuration) are small, and correctness of the fallback matters more than
 * eviction. It is thread-safe.
 *
 * @param <K> cache key type
 * @param <V> cached value type
 */
public class LastKnownGoodCache<K, V> {

    private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();

    /**
     * Reads through the loader, caching successful results and falling back to the last-known-good
     * value on a transient read failure.
     *
     * @param key    the cache key
     * @param loader the underlying read (typically a Datastore query)
     * @return the freshly loaded value, or the last-known-good value on transient failure
     * @throws MongoException if the read fails and no last-known-good value is cached
     */
    public V load(K key, Supplier<V> loader) {
        try {
            V value = loader.get();
            if (value != null) {
                cache.put(key, value);
            }
            return value;
        } catch (MongoException transientReadFailure) {
            V cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            throw transientReadFailure;
        }
    }

    /** Records a value as the last-known-good for a key (e.g. immediately after a successful write). */
    public void put(K key, V value) {
        if (value != null) {
            cache.put(key, value);
        }
    }

    /** Returns the currently cached value for a key, if any, without touching the Datastore. */
    public Optional<V> peek(K key) {
        return Optional.ofNullable(cache.get(key));
    }

    /** Removes any cached value for a key. */
    public void invalidate(K key) {
        cache.remove(key);
    }
}
