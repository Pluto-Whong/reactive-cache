package top.plutoppppp.reactive.cache.entry;

import java.lang.ref.ReferenceQueue;

import static top.plutoppppp.reactive.cache.ReactiveLocalCache.nullEntry;

public final class WeakWriteEntry<K, V> extends WeakEntry<K, V> {
    public WeakWriteEntry(ReferenceQueue<K> queue, K key, int hash, ReferenceEntry<K, V> next) {
        super(queue, key, hash, next);
    }

    // The code below is exactly the same for each write entry type.

    volatile long writeTime = Long.MAX_VALUE;

    @Override
    public long getWriteTime() {
        return writeTime;
    }

    @Override
    public void setWriteTime(long time) {
        this.writeTime = time;
    }

    // Guarded By Segment.this
    ReferenceEntry<K, V> nextWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
        return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
        this.nextWrite = next;
    }

    // Guarded By Segment.this
    ReferenceEntry<K, V> previousWrite = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
        return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
        this.previousWrite = previous;
    }
}
