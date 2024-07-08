package top.plutoppppp.reactive.cache.entry;

import top.plutoppppp.reactive.cache.ReactiveLocalCache;

public final class StrongWriteEntry<K, V> extends StrongEntry<K, V> {
    public StrongWriteEntry(K key, int hash, ReferenceEntry<K, V> next) {
        super(key, hash, next);
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
    ReferenceEntry<K, V> nextWrite = ReactiveLocalCache.nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInWriteQueue() {
        return nextWrite;
    }

    @Override
    public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
        this.nextWrite = next;
    }

    // Guarded By Segment.this
    ReferenceEntry<K, V> previousWrite = ReactiveLocalCache.nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInWriteQueue() {
        return previousWrite;
    }

    @Override
    public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
        this.previousWrite = previous;
    }
}
