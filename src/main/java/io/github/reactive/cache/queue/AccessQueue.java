package io.github.reactive.cache.queue;

import io.github.reactive.cache.common.AbstractSequentialIterator;
import io.github.reactive.cache.entry.AbstractReferenceEntry;
import io.github.reactive.cache.entry.NullEntry;
import io.github.reactive.cache.entry.ReferenceEntry;

import java.util.AbstractQueue;
import java.util.Iterator;

import static io.github.reactive.cache.ReactiveLocalCache.connectAccessOrder;
import static io.github.reactive.cache.ReactiveLocalCache.nullifyAccessOrder;

/**
 * A custom queue for managing access order. Note that this is tightly integrated with
 * {@code ReferenceEntry}, upon which it reliese to perform its linking.
 *
 * <p>Note that this entire implementation makes the assumption that all elements which are in the
 * map are also in this queue, and that all elements not in the queue are not in the map.
 *
 * <p>The benefits of creating our own queue are that (1) we can replace elements in the middle of
 * the queue as part of copyWriteEntry, and (2) the contains method is highly optimized for the
 * current model.
 */
public final class AccessQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {
        @Override
        public long getAccessTime() {
            return Long.MAX_VALUE;
        }

        @Override
        public void setAccessTime(long time) {
        }

        ReferenceEntry<K, V> nextAccess = this;

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        ReferenceEntry<K, V> previousAccess = this;

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }
    };

    // implements Queue

    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
        // unlink
        connectAccessOrder(entry.getPreviousInAccessQueue(), entry.getNextInAccessQueue());

        // add to tail
        connectAccessOrder(head.getPreviousInAccessQueue(), entry);
        connectAccessOrder(entry, head);

        return true;
    }

    @Override
    public ReferenceEntry<K, V> peek() {
        ReferenceEntry<K, V> next = head.getNextInAccessQueue();
        return (next == head) ? null : next;
    }

    @Override
    public ReferenceEntry<K, V> poll() {
        ReferenceEntry<K, V> next = head.getNextInAccessQueue();
        if (next == head) {
            return null;
        }

        remove(next);
        return next;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
        ReferenceEntry<K, V> e = (ReferenceEntry) o;
        ReferenceEntry<K, V> previous = e.getPreviousInAccessQueue();
        ReferenceEntry<K, V> next = e.getNextInAccessQueue();
        connectAccessOrder(previous, next);
        nullifyAccessOrder(e);

        return next != NullEntry.INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        ReferenceEntry<K, V> e = (ReferenceEntry) o;
        return e.getNextInAccessQueue() != NullEntry.INSTANCE;
    }

    @Override
    public boolean isEmpty() {
        return head.getNextInAccessQueue() == head;
    }

    @Override
    public int size() {
        int size = 0;
        for (ReferenceEntry<K, V> e = head.getNextInAccessQueue();
             e != head;
             e = e.getNextInAccessQueue()) {
            size++;
        }
        return size;
    }

    @Override
    public void clear() {
        ReferenceEntry<K, V> e = head.getNextInAccessQueue();
        while (e != head) {
            ReferenceEntry<K, V> next = e.getNextInAccessQueue();
            nullifyAccessOrder(e);
            e = next;
        }

        head.setNextInAccessQueue(head);
        head.setPreviousInAccessQueue(head);
    }

    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
        return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
            @Override
            protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                ReferenceEntry<K, V> next = previous.getNextInAccessQueue();
                return (next == head) ? null : next;
            }
        };
    }
}
