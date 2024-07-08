package top.plutoppppp.reactive.cache.queue;

import top.plutoppppp.reactive.cache.common.AbstractSequentialIterator;
import top.plutoppppp.reactive.cache.entry.AbstractReferenceEntry;
import top.plutoppppp.reactive.cache.entry.NullEntry;
import top.plutoppppp.reactive.cache.entry.ReferenceEntry;

import java.util.AbstractQueue;
import java.util.Iterator;

import static top.plutoppppp.reactive.cache.ReactiveLocalCache.connectWriteOrder;
import static top.plutoppppp.reactive.cache.ReactiveLocalCache.nullifyWriteOrder;

/**
 * A custom queue for managing eviction order. Note that this is tightly integrated with {@code
 * ReferenceEntry}, upon which it relies to perform its linking.
 *
 * <p>Note that this entire implementation makes the assumption that all elements which are in the
 * map are also in this queue, and that all elements not in the queue are not in the map.
 *
 * <p>The benefits of creating our own queue are that (1) we can replace elements in the middle of
 * the queue as part of copyWriteEntry, and (2) the contains method is highly optimized for the
 * current model.
 */
public final class WriteQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
    final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

        @Override
        public long getWriteTime() {
            return Long.MAX_VALUE;
        }

        @Override
        public void setWriteTime(long time) {
        }

        ReferenceEntry<K, V> nextWrite = this;

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        ReferenceEntry<K, V> previousWrite = this;

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    };

    // implements Queue

    @Override
    public boolean offer(ReferenceEntry<K, V> entry) {
        // unlink
        connectWriteOrder(entry.getPreviousInWriteQueue(), entry.getNextInWriteQueue());

        // add to tail
        connectWriteOrder(head.getPreviousInWriteQueue(), entry);
        connectWriteOrder(entry, head);

        return true;
    }

    @Override
    public ReferenceEntry<K, V> peek() {
        ReferenceEntry<K, V> next = head.getNextInWriteQueue();
        return (next == head) ? null : next;
    }

    @Override
    public ReferenceEntry<K, V> poll() {
        ReferenceEntry<K, V> next = head.getNextInWriteQueue();
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
        ReferenceEntry<K, V> previous = e.getPreviousInWriteQueue();
        ReferenceEntry<K, V> next = e.getNextInWriteQueue();
        connectWriteOrder(previous, next);
        nullifyWriteOrder(e);

        return next != NullEntry.INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        ReferenceEntry<K, V> e = (ReferenceEntry) o;
        return e.getNextInWriteQueue() != NullEntry.INSTANCE;
    }

    @Override
    public boolean isEmpty() {
        return head.getNextInWriteQueue() == head;
    }

    @Override
    public int size() {
        int size = 0;
        for (ReferenceEntry<K, V> e = head.getNextInWriteQueue();
             e != head;
             e = e.getNextInWriteQueue()) {
            size++;
        }
        return size;
    }

    @Override
    public void clear() {
        ReferenceEntry<K, V> e = head.getNextInWriteQueue();
        while (e != head) {
            ReferenceEntry<K, V> next = e.getNextInWriteQueue();
            nullifyWriteOrder(e);
            e = next;
        }

        head.setNextInWriteQueue(head);
        head.setPreviousInWriteQueue(head);
    }

    @Override
    public Iterator<ReferenceEntry<K, V>> iterator() {
        return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
            @Override
            protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                ReferenceEntry<K, V> next = previous.getNextInWriteQueue();
                return (next == head) ? null : next;
            }
        };
    }
}
