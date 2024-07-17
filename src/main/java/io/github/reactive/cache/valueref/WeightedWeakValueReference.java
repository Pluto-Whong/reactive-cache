package io.github.reactive.cache.valueref;

import io.github.reactive.cache.entry.ReferenceEntry;

import java.lang.ref.ReferenceQueue;

/**
 * References a weak value.
 */
public final class WeightedWeakValueReference<K, V> extends WeakValueReference<K, V> {
    final int weight;

    public WeightedWeakValueReference(
            ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry, int weight) {
        super(queue, referent, entry);
        this.weight = weight;
    }

    @Override
    public int getWeight() {
        return weight;
    }

    @Override
    public ValueReference<K, V> copyFor(
            ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
        return new WeightedWeakValueReference<>(queue, value, entry, weight);
    }
}
