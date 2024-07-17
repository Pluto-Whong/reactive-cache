package io.github.reactive.cache.entry;

import io.github.reactive.cache.valueref.ValueReference;

/**
 * An entry in a reference map.
 * <p>
 * Entries in the map can be in the following states:
 * <p>
 * Valid:
 * - Live: valid key/value are set
 * - Loading: loading is pending
 * <p>
 * Invalid:
 * - Expired: time expired (key/value may still be set)
 * - Collected: key/value was partially collected, but not yet cleaned up
 * - Unset: marked as unset, awaiting cleanup or reuse
 */
public interface ReferenceEntry<K, V> {
    /**
     * Returns the value reference from this entry.
     */
    ValueReference<K, V> getValueReference();

    /**
     * Sets the value reference for this entry.
     */
    void setValueReference(ValueReference<K, V> valueReference);

    /**
     * Returns the next entry in the chain.
     */
    ReferenceEntry<K, V> getNext();

    /**
     * Returns the entry's hash.
     */
    int getHash();

    /**
     * Returns the key for this entry.
     */
    K getKey();

    /*
     * Used by entries that use access order. Access entries are maintained in a doubly-linked list.
     * New entries are added at the tail of the list at write time; stale entries are expired from
     * the head of the list.
     */

    /**
     * Returns the time that this entry was last accessed, in ns.
     */
    long getAccessTime();

    /**
     * Sets the entry access time in ns.
     */
    void setAccessTime(long time);

    /**
     * Returns the next entry in the access queue.
     */
    ReferenceEntry<K, V> getNextInAccessQueue();

    /**
     * Sets the next entry in the access queue.
     */
    void setNextInAccessQueue(ReferenceEntry<K, V> next);

    /**
     * Returns the previous entry in the access queue.
     */
    ReferenceEntry<K, V> getPreviousInAccessQueue();

    /**
     * Sets the previous entry in the access queue.
     */
    void setPreviousInAccessQueue(ReferenceEntry<K, V> previous);

    /*
     * Implemented by entries that use write order. Write entries are maintained in a doubly-linked
     * list. New entries are added at the tail of the list at write time and stale entries are
     * expired from the head of the list.
     */

    /**
     * Returns the time that this entry was last written, in ns.
     */
    long getWriteTime();

    /**
     * Sets the entry write time in ns.
     */
    void setWriteTime(long time);

    /**
     * Returns the next entry in the write queue.
     */
    ReferenceEntry<K, V> getNextInWriteQueue();

    /**
     * Sets the next entry in the write queue.
     */
    void setNextInWriteQueue(ReferenceEntry<K, V> next);

    /**
     * Returns the previous entry in the write queue.
     */
    ReferenceEntry<K, V> getPreviousInWriteQueue();

    /**
     * Sets the previous entry in the write queue.
     */
    void setPreviousInWriteQueue(ReferenceEntry<K, V> previous);
}
