package io.github.reactive.cache.entry;

import io.github.reactive.cache.valueref.ValueReference;

public abstract class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
    @Override
    public ValueReference<K, V> getValueReference() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setValueReference(ValueReference<K, V> valueReference) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceEntry<K, V> getNext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getHash() {
        throw new UnsupportedOperationException();
    }

    @Override
    public K getKey() {
        throw new UnsupportedOperationException();
    }

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
}
