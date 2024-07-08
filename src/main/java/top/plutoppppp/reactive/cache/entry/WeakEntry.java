package top.plutoppppp.reactive.cache.entry;

import top.plutoppppp.reactive.cache.ReactiveLocalCache;
import top.plutoppppp.reactive.cache.valueref.ValueReference;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Used for weakly-referenced keys.
 */
public class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
    public WeakEntry(ReferenceQueue<K> queue, K key, int hash, ReferenceEntry<K, V> next) {
        super(key, queue);
        this.hash = hash;
        this.next = next;
    }

    @Override
    public K getKey() {
        return get();
    }

    /*
     * It'd be nice to get these for free from AbstractReferenceEntry, but we're already extending
     * WeakReference<K>.
     */

    // null access

    @Override
    public long getAccessTime() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAccessTime(long time) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
        throw new UnsupportedOperationException();
    }

    // null write

    @Override
    public long getWriteTime() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWriteTime(long time) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
        throw new UnsupportedOperationException();
    }

    // The code below is exactly the same for each entry type.

    final int hash;
    final ReferenceEntry<K, V> next;
    volatile ValueReference<K, V> valueReference = ReactiveLocalCache.unset();

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
