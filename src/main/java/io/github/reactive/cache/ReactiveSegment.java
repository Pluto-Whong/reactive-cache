package io.github.reactive.cache;

import io.github.reactive.cache.common.Assert;
import io.github.reactive.cache.entry.ReferenceEntry;
import io.github.reactive.cache.exception.InvalidCacheLoadException;
import io.github.reactive.cache.listener.RemovalCause;
import io.github.reactive.cache.listener.RemovalNotification;
import io.github.reactive.cache.lock.MonoReentrantLock;
import io.github.reactive.cache.queue.AccessQueue;
import io.github.reactive.cache.queue.WriteQueue;
import io.github.reactive.cache.stats.StatsCounter;
import io.github.reactive.cache.valueref.LoadingValueReference;
import io.github.reactive.cache.valueref.ValueReference;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.github.reactive.cache.ReactiveLocalCache.*;

/**
 * Segments are specialized versions of hash tables. This subclass inherits from ReentrantLock
 * opportunistically, just to simplify some locking and avoid separate construction.
 */
public class ReactiveSegment<K, V> {

    private static final Logger logger = Logger.getLogger(ReactiveSegment.class.getName());

    private final MonoReentrantLock lock = new MonoReentrantLock();

    final ReactiveLocalCache<K, V> map;

    /**
     * The number of live elements in this segment's region.
     */
    volatile int count;

    /**
     * The weight of the live elements in this segment's region.
     */
    long totalWeight;

    /**
     * Number of updates that alter the size of the table. This is used during bulk-read methods to
     * make sure they see a consistent snapshot: If modCounts change during a traversal of segments
     * loading size or checking containsValue, then we might have an inconsistent view of state so
     * (usually) must retry.
     */
    int modCount;

    /**
     * The table is expanded when its size exceeds this threshold. (The value of this field is
     * always {@code (int) (capacity * 0.75)}.)
     */
    int threshold;

    /**
     * The per-segment table.
     */
    volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;

    /**
     * The maximum weight of this segment. UNSET_INT if there is no maximum.
     */
    final long maxSegmentWeight;

    /**
     * The key reference queue contains entries whose keys have been garbage collected, and which
     * need to be cleaned up internally.
     */
    final ReferenceQueue<K> keyReferenceQueue;

    /**
     * The value reference queue contains value references whose values have been garbage collected,
     * and which need to be cleaned up internally.
     */
    final ReferenceQueue<V> valueReferenceQueue;

    /**
     * The recency queue is used to record which entries were accessed for updating the access
     * list's ordering. It is drained as a batch operation when either the DRAIN_THRESHOLD is
     * crossed or a write occurs on the segment.
     */
    final Queue<ReferenceEntry<K, V>> recencyQueue;

    /**
     * A counter of the number of reads since the last write, used to drain queues on a small
     * fraction of read operations.
     */
    final AtomicInteger readCount = new AtomicInteger();

    /**
     * A queue of elements currently in the map, ordered by write time. Elements are added to the
     * tail of the queue on write.
     */
    final Queue<ReferenceEntry<K, V>> writeQueue;

    /**
     * A queue of elements currently in the map, ordered by access time. Elements are added to the
     * tail of the queue on access (note that writes count as accesses).
     */
    final Queue<ReferenceEntry<K, V>> accessQueue;

    /**
     * Accumulates cache statistics.
     */
    final StatsCounter statsCounter;

    ReactiveSegment(
            ReactiveLocalCache<K, V> map,
            int initialCapacity,
            long maxSegmentWeight,
            StatsCounter statsCounter) {
        this.map = map;
        this.maxSegmentWeight = maxSegmentWeight;
        this.statsCounter = Objects.requireNonNull(statsCounter);
        initTable(newEntryArray(initialCapacity));

        keyReferenceQueue = map.usesKeyReferences() ? new ReferenceQueue<>() : null;

        valueReferenceQueue = map.usesValueReferences() ? new ReferenceQueue<>() : null;

        recencyQueue =
                map.usesAccessQueue()
                        ? new ConcurrentLinkedQueue<>()
                        : ReactiveLocalCache.discardingQueue();

        writeQueue =
                map.usesWriteQueue()
                        ? new WriteQueue<>()
                        : ReactiveLocalCache.discardingQueue();

        accessQueue =
                map.usesAccessQueue()
                        ? new AccessQueue<>()
                        : ReactiveLocalCache.discardingQueue();
    }

    AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
        return new AtomicReferenceArray<>(size);
    }

    void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
        this.threshold = newTable.length() * 3 / 4; // 0.75
        if (!map.customWeigher() && this.threshold == maxSegmentWeight) {
            // prevent spurious expansion before eviction
            this.threshold++;
        }
        this.table = newTable;
    }


    ReferenceEntry<K, V> newEntry(K key, int hash, ReferenceEntry<K, V> next) {
        return map.entryFactory.newEntry(this, Objects.requireNonNull(key), hash, next);
    }

    /**
     * Copies {@code original} into a new entry chained to {@code newNext}. Returns the new entry,
     * or {@code null} if {@code original} was already garbage collected.
     */
    ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        if (original.getKey() == null) {
            // key collected
            return null;
        }

        ValueReference<K, V> valueReference = original.getValueReference();
        V value = valueReference.get();
        if ((value == null) && valueReference.isActive()) {
            // value collected
            return null;
        }

        ReferenceEntry<K, V> newEntry = map.entryFactory.copyEntry(this, original, newNext);
        newEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, value, newEntry));
        return newEntry;
    }

    /**
     * Sets a new value of an entry. Adds newly created entries at the end of the access queue.
     */
    void setValue(ReferenceEntry<K, V> entry, K key, V value, long now) {
        ValueReference<K, V> previous = entry.getValueReference();
        int weight = map.weigher.weigh(key, value);
        Assert.checkState(weight >= 0, "Weights must be non-negative");

        ValueReference<K, V> valueReference =
                map.valueStrength.referenceValue(this, entry, value, weight);
        entry.setValueReference(valueReference);
        recordWrite(entry, weight, now);
        previous.notifyNewValue(value);
    }

    // loading

    Mono<V> get(K key, int hash, ReactiveCacheLoader<? super K, V> loader) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(loader);
        try {
            if (count != 0) { // read-volatile
                // don't call getLiveEntry, which would ignore loading values
                ReferenceEntry<K, V> e = getEntry(key, hash);
                if (e != null) {
                    long now = map.ticker.read();
                    V value = getLiveValue(e, now);
                    if (value != null) {
                        recordRead(e, now);
                        statsCounter.recordHits(1);
                        scheduleRefresh(e, key, hash, now, loader);
                        return Mono.just(value);
                    }
                    ValueReference<K, V> valueReference = e.getValueReference();
                    if (valueReference.isLoading()) {
                        return waitForLoadingValue(e, key, valueReference);
                    }
                }
            }

            // at this point e is either null or expired;
            return lockedGetOrLoad(key, hash, loader);
        } finally {
            postReadCleanup();
        }
    }

    Mono<V> lockedGetOrLoad(K key, int hash, ReactiveCacheLoader<? super K, V> loader) {
        return lock.lock(holder -> {
            ReferenceEntry<K, V> e;
            ValueReference<K, V> valueReference = null;
            LoadingValueReference<K, V> loadingValueReference = null;
            boolean createNewEntry = true;

            try {
                // re-read ticker once inside the lock
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        valueReference = e.getValueReference();
                        if (valueReference.isLoading()) {
                            createNewEntry = false;
                        } else {
                            V value = valueReference.get();
                            if (value == null) {
                                enqueueNotification(
                                        entryKey, hash, null, valueReference.getWeight(), RemovalCause.COLLECTED);
                            } else if (map.isExpired(e, now)) {
                                // This is a duplicate check, as preWriteCleanup already purged expired
                                // entries, but let's accomodate an incorrect expiration queue.
                                enqueueNotification(
                                        entryKey, hash, value, valueReference.getWeight(), RemovalCause.EXPIRED);
                            } else {
                                recordLockedRead(e, now);
                                statsCounter.recordHits(1);
                                // we were concurrent with loading; don't consider refresh
                                return Mono.just(value);
                            }

                            // immediately reuse invalid entries
                            writeQueue.remove(e);
                            accessQueue.remove(e);
                            this.count = newCount; // write-volatile
                        }
                        break;
                    }
                }

                if (createNewEntry) {
                    loadingValueReference = new LoadingValueReference<>();

                    if (e == null) {
                        e = newEntry(key, hash, first);
                        e.setValueReference(loadingValueReference);
                        table.set(index, e);
                    } else {
                        e.setValueReference(loadingValueReference);
                    }
                }
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }

            if (createNewEntry) {
                try {
                    return loadSync(key, hash, loadingValueReference, loader);
                } finally {
                    statsCounter.recordMisses(1);
                }
            } else {
                // The entry already exists. Wait for loading.
                return waitForLoadingValue(e, key, valueReference);
            }
        });
    }

    Mono<V> waitForLoadingValue(ReferenceEntry<K, V> e, K key, ValueReference<K, V> valueReference) {
        if (!valueReference.isLoading()) {
            throw new AssertionError();
        }

        try {
            Mono<V> valueMono = Mono.create(sink -> valueReference.waitForValue(sink, map.loadingRestartScheduler));
            if (map.timeoutNanos > 0) {
                valueMono = valueMono.timeout(Duration.ofNanos(map.timeoutNanos), map.timeoutScheduler);
            }

            return valueMono.switchIfEmpty(Mono.error(() ->
                    new InvalidCacheLoadException("ReactiveCacheLoader returned null for key " + key + ".")
            )).doOnNext(v -> {
                // re-read ticker now that loading has completed
                long now = map.ticker.read();
                recordRead(e, now);
            });
        } finally {
            statsCounter.recordMisses(1);
        }
    }

    // at most one of loadSync/loadAsync may be called for any given LoadingValueReference

    Mono<V> loadSync(
            K key,
            int hash,
            LoadingValueReference<K, V> loadingValueReference,
            ReactiveCacheLoader<? super K, V> loader) {
        Mono<V> loadingMono = loadingValueReference.loadFuture(key, loader, null);
        return getAndRecordStats(key, hash, loadingValueReference, loadingMono);
    }

    void loadAsync(
            final K key,
            final int hash,
            final LoadingValueReference<K, V> loadingValueReference,
            ReactiveCacheLoader<? super K, V> loader) {
        Scheduler loadingRestartScheduler = Objects.nonNull(map.loadingRestartScheduler) ? map.loadingRestartScheduler : Schedulers.parallel();

        Mono<V> loadingMono = loadingValueReference.loadFuture(key, loader, loadingRestartScheduler);
        getAndRecordStats(key, hash, loadingValueReference, loadingMono)
                .onErrorResume(e -> {
                    logger.log(Level.WARNING, "Exception thrown during refresh", e);
                    loadingValueReference.setException(e);
                    return Mono.empty();
                }).subscribe();

    }

    Mono<V> getAndRecordStats(
            K key,
            int hash,
            LoadingValueReference<K, V> loadingValueReference,
            Mono<V> newValue) {
        return newValue
                .switchIfEmpty(Mono.error(() ->
                        new InvalidCacheLoadException("ReactiveCacheLoader returned null")
                ))
                .flatMap(value -> {
                    statsCounter.recordLoadSuccess(loadingValueReference.elapsedNanos());
                    return storeLoadedValue(key, hash, loadingValueReference, value)
                            .thenReturn(value);
                })
                .onErrorResume(e -> {
                    statsCounter.recordLoadException(loadingValueReference.elapsedNanos());
                    return removeLoadingValue(key, hash, loadingValueReference).then(Mono.error(e));
                });
    }

    void scheduleRefresh(
            ReferenceEntry<K, V> entry,
            K key,
            int hash,
            long now,
            ReactiveCacheLoader<? super K, V> loader) {
        if (map.refreshes()
                && (now - entry.getWriteTime() > map.refreshNanos)
                && !entry.getValueReference().isLoading()) {
            refresh(key, hash, loader, true);
        }
    }

    /**
     * Refreshes the value associated with {@code key}, unless another thread is already doing so.
     * Returns the newly refreshed value associated with {@code key} if it was refreshed inline, or
     * {@code null} if another thread is performing the refresh or if an error occurs during
     * refresh.
     */
    void refresh(K key, int hash, ReactiveCacheLoader<? super K, V> loader, boolean checkTime) {
        insertLoadingValueReference(key, hash, checkTime)
                .subscribe(loadingValueReference ->
                        loadAsync(key, hash, loadingValueReference, loader)
                );
    }

    /**
     * Returns a newly inserted {@code LoadingValueReference}, or null if the live value reference
     * is already loading.
     */

    Mono<LoadingValueReference<K, V>> insertLoadingValueReference(
            final K key, final int hash, boolean checkTime) {
        return lock.lock(holder -> {
            ReferenceEntry<K, V> e;
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        if (valueReference.isLoading()
                                || (checkTime && (now - e.getWriteTime() < map.refreshNanos))) {
                            // refresh is a no-op if loading is pending
                            // if checkTime, we want to check *after* acquiring the lock if refresh still needs
                            // to be scheduled
                            return Mono.empty();
                        }

                        // continue returning old value while loading
                        ++modCount;
                        LoadingValueReference<K, V> loadingValueReference =
                                new LoadingValueReference<>(valueReference);
                        e.setValueReference(loadingValueReference);
                        return Mono.just(loadingValueReference);
                    }
                }

                ++modCount;
                LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<>();
                e = newEntry(key, hash, first);
                e.setValueReference(loadingValueReference);
                table.set(index, e);
                return Mono.just(loadingValueReference);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    // reference queues, for garbage collection cleanup

    /**
     * Cleanup collected entries when the lock is available.
     */
    void tryDrainReferenceQueues() {
        lock.tryLock(holder -> {
            try {
                drainReferenceQueues();
            } finally {
                holder.unlock();
            }
        });
    }

    /**
     * Drain the key and value reference queues, cleaning up internal entries containing garbage
     * collected keys or values.
     */

    void drainReferenceQueues() {
        if (map.usesKeyReferences()) {
            drainKeyReferenceQueue();
        }
        if (map.usesValueReferences()) {
            drainValueReferenceQueue();
        }
    }


    void drainKeyReferenceQueue() {
        Reference<? extends K> ref;
        int i = 0;
        while ((ref = keyReferenceQueue.poll()) != null) {
            @SuppressWarnings("unchecked")
            ReferenceEntry<K, V> entry = (ReferenceEntry<K, V>) ref;
            map.reclaimKey(entry);
            if (++i == DRAIN_MAX) {
                break;
            }
        }
    }


    void drainValueReferenceQueue() {
        Reference<? extends V> ref;
        int i = 0;
        while ((ref = valueReferenceQueue.poll()) != null) {
            @SuppressWarnings("unchecked")
            ValueReference<K, V> valueReference = (ValueReference<K, V>) ref;
            map.reclaimValue(valueReference);
            if (++i == DRAIN_MAX) {
                break;
            }
        }
    }

    /**
     * Clears all entries from the key and value reference queues.
     */
    void clearReferenceQueues() {
        if (map.usesKeyReferences()) {
            clearKeyReferenceQueue();
        }
        if (map.usesValueReferences()) {
            clearValueReferenceQueue();
        }
    }

    void clearKeyReferenceQueue() {
        while (keyReferenceQueue.poll() != null) {
            // do nothing
        }
    }

    void clearValueReferenceQueue() {
        while (valueReferenceQueue.poll() != null) {
            // do nothing
        }
    }

    // recency queue, shared by expiration and eviction

    /**
     * Records the relative order in which this read was performed by adding {@code entry} to the
     * recency queue. At write-time, or when the queue is full past the threshold, the queue will be
     * drained and the entries therein processed.
     *
     * <p>Note: locked reads should use {@link #recordLockedRead}.
     */
    void recordRead(ReferenceEntry<K, V> entry, long now) {
        if (map.recordsAccess()) {
            entry.setAccessTime(now);
        }
        recencyQueue.add(entry);
    }

    /**
     * Updates the eviction metadata that {@code entry} was just read. This currently amounts to
     * adding {@code entry} to relevant eviction lists.
     *
     * <p>Note: this method should only be called under lock, as it directly manipulates the
     * eviction queues. Unlocked reads should use {@link #recordRead}.
     */
    void recordLockedRead(ReferenceEntry<K, V> entry, long now) {
        if (map.recordsAccess()) {
            entry.setAccessTime(now);
        }
        accessQueue.add(entry);
    }

    /**
     * Updates eviction metadata that {@code entry} was just written. This currently amounts to
     * adding {@code entry} to relevant eviction lists.
     */
    void recordWrite(ReferenceEntry<K, V> entry, int weight, long now) {
        // we are already under lock, so drain the recency queue immediately
        drainRecencyQueue();
        totalWeight += weight;

        if (map.recordsAccess()) {
            entry.setAccessTime(now);
        }
        if (map.recordsWrite()) {
            entry.setWriteTime(now);
        }
        accessQueue.add(entry);
        writeQueue.add(entry);
    }

    /**
     * Drains the recency queue, updating eviction metadata that the entries therein were read in
     * the specified relative order. This currently amounts to adding them to relevant eviction
     * lists (accounting for the fact that they could have been removed from the map since being
     * added to the recency queue).
     */
    void drainRecencyQueue() {
        ReferenceEntry<K, V> e;
        while ((e = recencyQueue.poll()) != null) {
            // An entry may be in the recency queue despite it being removed from
            // the map . This can occur when the entry was concurrently read while a
            // writer is removing it from the segment or after a clear has removed
            // all the segment's entries.
            if (accessQueue.contains(e)) {
                accessQueue.add(e);
            }
        }
    }

    // expiration

    /**
     * Cleanup expired entries when the lock is available.
     */
    void tryExpireEntries(long now) {
        lock.tryLock(holder -> {
            try {
                expireEntries(now);
            } finally {
                holder.unlock();
                // don't call postWriteCleanup as we're in a read
            }
        });
    }

    void expireEntries(long now) {
        drainRecencyQueue();

        ReferenceEntry<K, V> e;
        while ((e = writeQueue.peek()) != null && map.isExpired(e, now)) {
            if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                throw new AssertionError();
            }
        }
        while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
            if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                throw new AssertionError();
            }
        }
    }

    // eviction

    void enqueueNotification(
            K key, int hash, V value, int weight, RemovalCause cause) {
        totalWeight -= weight;
        if (cause.wasEvicted()) {
            statsCounter.recordEviction();
        }
        if (map.removalNotificationQueue != DISCARDING_QUEUE) {
            RemovalNotification<K, V> notification = RemovalNotification.create(key, value, cause);
            map.removalNotificationQueue.offer(notification);
        }
    }

    /**
     * Performs eviction if the segment is over capacity. Avoids flushing the entire cache if the
     * newest entry exceeds the maximum weight all on its own.
     *
     * @param newest the most recently added entry
     */
    void evictEntries(ReferenceEntry<K, V> newest) {
        if (!map.evictsBySize()) {
            return;
        }

        drainRecencyQueue();

        // If the newest entry by itself is too heavy for the segment, don't bother evicting
        // anything else, just that
        if (newest.getValueReference().getWeight() > maxSegmentWeight) {
            if (!removeEntry(newest, newest.getHash(), RemovalCause.SIZE)) {
                throw new AssertionError();
            }
        }

        while (totalWeight > maxSegmentWeight) {
            ReferenceEntry<K, V> e = getNextEvictable();
            if (!removeEntry(e, e.getHash(), RemovalCause.SIZE)) {
                throw new AssertionError();
            }
        }
    }

    ReferenceEntry<K, V> getNextEvictable() {
        for (ReferenceEntry<K, V> e : accessQueue) {
            int weight = e.getValueReference().getWeight();
            if (weight > 0) {
                return e;
            }
        }
        throw new AssertionError();
    }

    /**
     * Returns first entry of bin for given hash.
     */
    ReferenceEntry<K, V> getFirst(int hash) {
        // read this volatile field only once
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        return table.get(hash & (table.length() - 1));
    }

    // Specialized implementations of map methods

    ReferenceEntry<K, V> getEntry(Object key, int hash) {
        for (ReferenceEntry<K, V> e = getFirst(hash); e != null; e = e.getNext()) {
            if (e.getHash() != hash) {
                continue;
            }

            K entryKey = e.getKey();
            if (entryKey == null) {
                tryDrainReferenceQueues();
                continue;
            }

            if (map.keyEquivalence.equivalent(key, entryKey)) {
                return e;
            }
        }

        return null;
    }


    ReferenceEntry<K, V> getLiveEntry(Object key, int hash, long now) {
        ReferenceEntry<K, V> e = getEntry(key, hash);
        if (e == null) {
            return null;
        } else if (map.isExpired(e, now)) {
            tryExpireEntries(now);
            return null;
        }
        return e;
    }

    /**
     * Gets the value from an entry. Returns null if the entry is invalid, partially-collected,
     * loading, or expired.
     */
    V getLiveValue(ReferenceEntry<K, V> entry, long now) {
        if (entry.getKey() == null) {
            tryDrainReferenceQueues();
            return null;
        }
        V value = entry.getValueReference().get();
        if (value == null) {
            tryDrainReferenceQueues();
            return null;
        }

        if (map.isExpired(entry, now)) {
            tryExpireEntries(now);
            return null;
        }
        return value;
    }


    Mono<V> get(Object key, int hash) {
        try {
            if (count != 0) { // read-volatile
                long now = map.ticker.read();
                ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                if (e == null) {
                    return Mono.empty();
                }

                V value = e.getValueReference().get();
                if (value != null) {
                    recordRead(e, now);
                    scheduleRefresh(e, e.getKey(), hash, now, map.defaultLoader);
                    return Mono.just(value);
                }
                tryDrainReferenceQueues();
            }
            return Mono.empty();
        } finally {
            postReadCleanup();
        }
    }

    boolean containsKey(Object key, int hash) {
        try {
            if (count != 0) { // read-volatile
                long now = map.ticker.read();
                ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                if (e == null) {
                    return false;
                }
                return e.getValueReference().get() != null;
            }

            return false;
        } finally {
            postReadCleanup();
        }
    }

    /**
     * This method is a convenience for testing. Code should call {@link ReactiveLocalCache#containsValue}
     * directly.
     */
    boolean containsValue(Object value) {
        try {
            if (count != 0) { // read-volatile
                long now = map.ticker.read();
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int length = table.length();
                for (int i = 0; i < length; ++i) {
                    for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                        V entryValue = getLiveValue(e, now);
                        if (entryValue == null) {
                            continue;
                        }
                        if (map.valueEquivalence.equivalent(value, entryValue)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        } finally {
            postReadCleanup();
        }
    }


    Mono<V> put(K key, int hash, V value, boolean onlyIfAbsent) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // ensure capacity
                    expand();
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        if (entryValue == null) {
                            ++modCount;
                            if (valueReference.isActive()) {
                                enqueueNotification(
                                        key, hash, null, valueReference.getWeight(), RemovalCause.COLLECTED);
                                setValue(e, key, value, now);
                                newCount = this.count; // count remains unchanged
                            } else {
                                setValue(e, key, value, now);
                                newCount = this.count + 1;
                            }
                            this.count = newCount; // write-volatile
                            evictEntries(e);
                            return Mono.empty();
                        } else if (onlyIfAbsent) {
                            // Mimic
                            // "if (!map.containsKey(key)) ...
                            // else return map.get(key);
                            recordLockedRead(e, now);
                            return Mono.just(entryValue);
                        } else {
                            // clobber existing entry, count remains unchanged
                            ++modCount;
                            enqueueNotification(
                                    key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
                            setValue(e, key, value, now);
                            evictEntries(e);
                            return Mono.just(entryValue);
                        }
                    }
                }

                // Create a new entry.
                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, value, now);
                table.set(index, newEntry);
                newCount = this.count + 1;
                this.count = newCount; // write-volatile
                evictEntries(newEntry);
                return Mono.empty();
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    /**
     * Expands the table if possible.
     */

    void expand() {
        AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;
        int oldCapacity = oldTable.length();
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            return;
        }

        /*
         * Reclassify nodes in each list to new Map. Because we are using power-of-two expansion, the
         * elements from each bin must either stay at same index, or move with a power of two offset.
         * We eliminate unnecessary node creation by catching cases where old nodes can be reused
         * because their next fields won't change. Statistically, at the default threshold, only about
         * one-sixth of them need cloning when a table doubles. The nodes they replace will be garbage
         * collectable as soon as they are no longer referenced by any reader thread that may be in
         * the midst of traversing table right now.
         */

        int newCount = count;
        AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
        threshold = newTable.length() * 3 / 4;
        int newMask = newTable.length() - 1;
        for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
            // We need to guarantee that any existing reads of old Map can
            // proceed. So we cannot yet null out each bin.
            ReferenceEntry<K, V> head = oldTable.get(oldIndex);

            if (head != null) {
                ReferenceEntry<K, V> next = head.getNext();
                int headIndex = head.getHash() & newMask;

                // Single node on list
                if (next == null) {
                    newTable.set(headIndex, head);
                } else {
                    // Reuse the consecutive sequence of nodes with the same target
                    // index from the end of the list. tail points to the first
                    // entry in the reusable list.
                    ReferenceEntry<K, V> tail = head;
                    int tailIndex = headIndex;
                    for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
                        int newIndex = e.getHash() & newMask;
                        if (newIndex != tailIndex) {
                            // The index changed. We'll need to copy the previous entry.
                            tailIndex = newIndex;
                            tail = e;
                        }
                    }
                    newTable.set(tailIndex, tail);

                    // Clone nodes leading up to the tail.
                    for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
                        int newIndex = e.getHash() & newMask;
                        ReferenceEntry<K, V> newNext = newTable.get(newIndex);
                        ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
                        if (newFirst != null) {
                            newTable.set(newIndex, newFirst);
                        } else {
                            removeCollectedEntry(e);
                            newCount--;
                        }
                    }
                }
            }
        }
        table = newTable;
        this.count = newCount;
    }

    Mono<Boolean> replace(K key, int hash, V oldValue, V newValue) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst =
                                        removeValueFromChain(
                                                first,
                                                e,
                                                entryKey,
                                                hash,
                                                null,
                                                valueReference,
                                                RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return Mono.just(false);
                        }

                        if (map.valueEquivalence.equivalent(oldValue, entryValue)) {
                            ++modCount;
                            enqueueNotification(
                                    key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
                            setValue(e, key, newValue, now);
                            evictEntries(e);
                            return Mono.just(true);
                        } else {
                            // Mimic
                            // "if (map.containsKey(key) && map.get(key).equals(oldValue))..."
                            recordLockedRead(e, now);
                            return Mono.just(false);
                        }
                    }
                }

                return Mono.just(false);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });

    }


    Mono<V> replace(K key, int hash, V newValue) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst =
                                        removeValueFromChain(
                                                first,
                                                e,
                                                entryKey,
                                                hash,
                                                null,
                                                valueReference,
                                                RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return Mono.empty();
                        }

                        ++modCount;
                        enqueueNotification(
                                key, hash, entryValue, valueReference.getWeight(), RemovalCause.REPLACED);
                        setValue(e, key, newValue, now);
                        evictEntries(e);
                        return Mono.just(entryValue);
                    }
                }

                return Mono.empty();
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }


    Mono<V> remove(Object key, int hash) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                int newCount;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (entryValue != null) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return Mono.empty();
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst =
                                removeValueFromChain(first, e, entryKey, hash, entryValue, valueReference, cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return Mono.just(entryValue);
                    }
                }

                return Mono.empty();
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    Mono<Boolean> storeLoadedValue(
            K key, int hash, LoadingValueReference<K, V> oldValueReference, V newValue) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // ensure capacity
                    expand();
                    newCount = this.count + 1;
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        // replace the old LoadingValueReference if it's live, otherwise
                        // perform a putIfAbsent
                        if (oldValueReference == valueReference
                                || (entryValue == null && valueReference != UNSET)) {
                            ++modCount;
                            if (oldValueReference.isActive()) {
                                RemovalCause cause =
                                        (entryValue == null) ? RemovalCause.COLLECTED : RemovalCause.REPLACED;
                                enqueueNotification(key, hash, entryValue, oldValueReference.getWeight(), cause);
                                newCount--;
                            }
                            setValue(e, key, newValue, now);
                            this.count = newCount; // write-volatile
                            evictEntries(e);
                            return Mono.just(true);
                        }

                        // the loaded value was already clobbered
                        enqueueNotification(key, hash, newValue, 0, RemovalCause.REPLACED);
                        return Mono.just(false);
                    }
                }

                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, newValue, now);
                table.set(index, newEntry);
                this.count = newCount; // write-volatile
                evictEntries(newEntry);
                return Mono.just(true);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    Mono<Boolean> remove(Object key, int hash, Object value) {
        return lock.lock(holder -> {
            try {
                long now = map.ticker.read();
                preWriteCleanup(now, holder);

                int newCount;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (map.valueEquivalence.equivalent(value, entryValue)) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (entryValue == null && valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return Mono.just(false);
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst =
                                removeValueFromChain(first, e, entryKey, hash, entryValue, valueReference, cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return Mono.just(cause == RemovalCause.EXPLICIT);
                    }
                }

                return Mono.just(false);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    Mono<Void> clear() {
        if (count != 0) { // read-volatile
            return lock.lock(holder -> {
                try {
                    long now = map.ticker.read();
                    preWriteCleanup(now, holder);

                    AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                    for (int i = 0; i < table.length(); ++i) {
                        for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                            // Loading references aren't actually in the map yet.
                            if (e.getValueReference().isActive()) {
                                K key = e.getKey();
                                V value = e.getValueReference().get();
                                RemovalCause cause =
                                        (key == null || value == null) ? RemovalCause.COLLECTED : RemovalCause.EXPLICIT;
                                enqueueNotification(
                                        key, e.getHash(), value, e.getValueReference().getWeight(), cause);
                            }
                        }
                    }
                    for (int i = 0; i < table.length(); ++i) {
                        table.set(i, null);
                    }
                    clearReferenceQueues();
                    writeQueue.clear();
                    accessQueue.clear();
                    readCount.set(0);

                    ++modCount;
                    count = 0; // write-volatile
                } finally {
                    holder.unlock();
                    postWriteCleanup(holder);
                }
            });
        }
        return Mono.empty();
    }


    ReferenceEntry<K, V> removeValueFromChain(
            ReferenceEntry<K, V> first,
            ReferenceEntry<K, V> entry,
            K key,
            int hash,
            V value,
            ValueReference<K, V> valueReference,
            RemovalCause cause) {
        enqueueNotification(key, hash, value, valueReference.getWeight(), cause);
        writeQueue.remove(entry);
        accessQueue.remove(entry);

        if (valueReference.isLoading()) {
            valueReference.notifyNewValue(null);
            return first;
        } else {
            return removeEntryFromChain(first, entry);
        }
    }


    ReferenceEntry<K, V> removeEntryFromChain(
            ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry) {
        int newCount = count;
        ReferenceEntry<K, V> newFirst = entry.getNext();
        for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
            ReferenceEntry<K, V> next = copyEntry(e, newFirst);
            if (next != null) {
                newFirst = next;
            } else {
                removeCollectedEntry(e);
                newCount--;
            }
        }
        this.count = newCount;
        return newFirst;
    }


    void removeCollectedEntry(ReferenceEntry<K, V> entry) {
        enqueueNotification(
                entry.getKey(),
                entry.getHash(),
                entry.getValueReference().get(),
                entry.getValueReference().getWeight(),
                RemovalCause.COLLECTED);
        writeQueue.remove(entry);
        accessQueue.remove(entry);
    }

    /**
     * Removes an entry whose key has been garbage collected.
     */
    Mono<Boolean> reclaimKey(ReferenceEntry<K, V> entry, int hash) {
        return lock.lock(holder -> {
            try {
                int newCount;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    if (e == entry) {
                        ++modCount;
                        ReferenceEntry<K, V> newFirst =
                                removeValueFromChain(
                                        first,
                                        e,
                                        e.getKey(),
                                        hash,
                                        e.getValueReference().get(),
                                        e.getValueReference(),
                                        RemovalCause.COLLECTED);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return Mono.just(true);
                    }
                }

                return Mono.just(false);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }

    /**
     * Removes an entry whose value has been garbage collected.
     */
    Mono<Boolean> reclaimValue(K key, int hash, ValueReference<K, V> valueReference) {
        return lock.lock(holder -> {
            try {
                int newCount;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            ++modCount;
                            ReferenceEntry<K, V> newFirst =
                                    removeValueFromChain(
                                            first,
                                            e,
                                            entryKey,
                                            hash,
                                            valueReference.get(),
                                            valueReference,
                                            RemovalCause.COLLECTED);
                            newCount = this.count - 1;
                            table.set(index, newFirst);
                            this.count = newCount; // write-volatile
                            return Mono.just(true);
                        }
                        return Mono.just(false);
                    }
                }

                return Mono.just(false);
            } finally {
                holder.unlock();
                if (!lock.isHeldByCurrentChain(holder)) { // don't clean up inside of put
                    postWriteCleanup(holder);
                }
            }
        });
    }

    Mono<Boolean> removeLoadingValue(K key, int hash, LoadingValueReference<K, V> valueReference) {
        return lock.lock(holder -> {
            try {
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash
                            && entryKey != null
                            && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            if (valueReference.isActive()) {
                                e.setValueReference(valueReference.getOldValue());
                            } else {
                                ReferenceEntry<K, V> newFirst = removeEntryFromChain(first, e);
                                table.set(index, newFirst);
                            }
                            return Mono.just(true);
                        }
                        return Mono.just(false);
                    }
                }

                return Mono.just(false);
            } finally {
                holder.unlock();
                postWriteCleanup(holder);
            }
        });
    }


    boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
        int newCount;
        AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
        int index = hash & (table.length() - 1);
        ReferenceEntry<K, V> first = table.get(index);

        for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
            if (e == entry) {
                ++modCount;
                ReferenceEntry<K, V> newFirst =
                        removeValueFromChain(
                                first,
                                e,
                                e.getKey(),
                                hash,
                                e.getValueReference().get(),
                                e.getValueReference(),
                                cause);
                newCount = this.count - 1;
                table.set(index, newFirst);
                this.count = newCount; // write-volatile
                return true;
            }
        }

        return false;
    }

    /**
     * Performs routine cleanup following a read. Normally cleanup happens during writes. If cleanup
     * is not observed after a sufficient number of reads, try cleaning up from the read thread.
     */
    void postReadCleanup() {
        if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
            cleanUp();
        }
    }

    /**
     * Performs routine cleanup prior to executing a write. This should be called every time a write
     * thread acquires the segment lock, immediately after acquiring the lock.
     *
     * <p>Post-condition: expireEntries has been run.
     */

    void preWriteCleanup(long now, MonoReentrantLock.LockHolder lockHolder) {
        runLockedCleanup(now, lockHolder);
    }

    /**
     * Performs routine cleanup following a write.
     */
    void postWriteCleanup(MonoReentrantLock.LockHolder lockHolder) {
        runUnlockedCleanup(lockHolder);
    }

    void cleanUp() {
        long now = map.ticker.read();
        runLockedCleanup(now, null);
        runUnlockedCleanup(null);
    }

    void runLockedCleanup(long now, MonoReentrantLock.LockHolder lockHolder) {
        lock.tryLock(holder -> {
            try {
                drainReferenceQueues();
                expireEntries(now); // calls drainRecencyQueue
                readCount.set(0);
            } finally {
                holder.unlock();
            }
        }, lockHolder);
    }

    void runUnlockedCleanup(MonoReentrantLock.LockHolder lockHolder) {
        // locked cleanup may generate notifications we can send unlocked
        if (!lock.isHeldByCurrentChain(lockHolder)) {
            map.processPendingNotifications();
        }
    }
}
