package io.github.reactive.cache.entry;

import static io.github.reactive.cache.ReactiveLocalCache.nullEntry;

public final class StrongAccessEntry<K, V> extends StrongEntry<K, V> {

    public StrongAccessEntry(K key, int hash, ReferenceEntry<K, V> next) {
        super(key, hash, next);
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

    ReferenceEntry<K, V> nextAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getNextInAccessQueue() {
        return nextAccess;
    }

    @Override
    public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
        this.nextAccess = next;
    }

    ReferenceEntry<K, V> previousAccess = nullEntry();

    @Override
    public ReferenceEntry<K, V> getPreviousInAccessQueue() {
        return previousAccess;
    }

    @Override
    public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
        this.previousAccess = previous;
    }
}
