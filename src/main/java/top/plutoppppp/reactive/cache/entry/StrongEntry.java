package top.plutoppppp.reactive.cache.entry;

import top.plutoppppp.reactive.cache.valueref.ValueReference;

import static top.plutoppppp.reactive.cache.ReactiveLocalCache.unset;

/*
 * Note: All of this duplicate code sucks, but it saves a lot of memory. If only Java had mixins!
 * To maintain this code, make a change for the strong reference type. Then, cut and paste, and
 * replace "Strong" with "Soft" or "Weak" within the pasted text. The primary difference is that
 * strong entries store the key reference directly while soft and weak entries delegate to their
 * respective superclasses.
 */

/**
 * Used for strongly-referenced keys.
 */
public class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {
    final K key;

    public StrongEntry(K key, int hash, ReferenceEntry<K, V> next) {
        this.key = key;
        this.hash = hash;
        this.next = next;
    }

    @Override
    public K getKey() {
        return this.key;
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = unset();

    @Override
    public ValueReference<K, V> getValueReference() {
        return valueReference;
    }

    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
        this.valueReference = valueReference;
    }

    @Override
    public int getHash() {
        return hash;
    }

    @Override
    public ReferenceEntry<K, V> getNext() {
        return next;
    }
}