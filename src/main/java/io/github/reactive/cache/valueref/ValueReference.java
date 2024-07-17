package io.github.reactive.cache.valueref;

import io.github.reactive.cache.entry.ReferenceEntry;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;

import java.lang.ref.ReferenceQueue;

/**
 * A reference to a value.
 */
public interface ValueReference<K, V> {
    /**
     * Returns the value. Does not block or throw exceptions.
     */
    V get();

    void waitForValue(MonoSink<V> sink, Scheduler scheduler);

    /**
     * Returns the weight of this entry. This is assumed to be static between calls to setValue.
     */
    int getWeight();

    /**
     * Returns the entry associated with this value reference, or {@code null} if this value
     * reference is independent of any entry.
     */
    ReferenceEntry<K, V> getEntry();

    /**
     * Creates a copy of this reference for the given entry.
     *
     * <p>{@code value} may be null only for a loading reference.
     */
    ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry);

    /**
     * Notifify pending loads that a new value was set. This is only relevant to loading value
     * references.
     */
    void notifyNewValue(V newValue);

    /**
     * Returns true if a new value is currently loading, regardless of whether or not there is an
     * existing value. It is assumed that the return value of this method is constant for any given
     * ValueReference instance.
     */
    boolean isLoading();

    /**
     * Returns true if this reference contains an active value, meaning one that is still considered
     * present in the cache. Active values consist of live values, which are returned by cache
     * lookups, and dead values, which have been evicted but awaiting removal. Non-active values
     * consist strictly of loading values, though during refresh a value may be both active and
     * loading.
     */
    boolean isActive();
}
