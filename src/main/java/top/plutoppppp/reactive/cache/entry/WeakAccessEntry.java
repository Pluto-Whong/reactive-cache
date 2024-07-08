package top.plutoppppp.reactive.cache.entry;

import top.plutoppppp.reactive.cache.ReactiveLocalCache;

import java.lang.ref.ReferenceQueue;

public final class WeakAccessEntry<K, V> extends WeakEntry<K, V> {
    public WeakAccessEntry(ReferenceQueue<K> queue, K key, int hash, ReferenceEntry<K, V> next) {
        super(queue, key, hash, next);
    }

    // The code below is exactly the same for each access entry type.

    volatile long accessTime = Long.MAX_VALUE;

    @Override
    public long getAccessTime() {
        return accessTime;
    }

    @Override
    public void setAccessTime(long time) {
        this.accessTime = time;
    }

    // Guarded By Segment.this
    ReferenceEntry<K, V> nextAccess = ReactiveLocalCache.nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
        return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
        this.nextAccess = next;
    }

    // Guarded By Segment.this
    ReferenceEntry<K, V> previousAccess = ReactiveLocalCache.nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
        return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
        this.previousAccess = previous;
    }
}
