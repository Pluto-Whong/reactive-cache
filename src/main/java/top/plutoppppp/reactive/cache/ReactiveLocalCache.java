package top.plutoppppp.reactive.cache;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import top.plutoppppp.reactive.cache.common.Equivalence;
import top.plutoppppp.reactive.cache.common.Ticker;
import top.plutoppppp.reactive.cache.entry.NullEntry;
import top.plutoppppp.reactive.cache.entry.ReferenceEntry;
import top.plutoppppp.reactive.cache.listener.RemovalListener;
import top.plutoppppp.reactive.cache.listener.RemovalNotification;
import top.plutoppppp.reactive.cache.stats.StatsCounter;
import top.plutoppppp.reactive.cache.valueref.ValueReference;

import java.lang.ref.ReferenceQueue;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;

import static top.plutoppppp.reactive.cache.ReactiveCacheBuilder.UNSET_INT;

public final class ReactiveLocalCache<K, V> implements ConcurrentMap<K, Mono<V>> {

    private static final Logger logger = Logger.getLogger(ReactiveLocalCache.class.getName());

    /**
     * The maximum capacity, used if a higher value is implicitly specified by either of the
     * constructors with arguments. MUST be a power of two <= 1<<30 to ensure that entries are
     * indexable using ints.
     */
    static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * The maximum number of segments to allow; used to bound constructor arguments.
     */
    static final int MAX_SEGMENTS = 1 << 16; // slightly conservative

    /**
     * Number of (unsynchronized) retries in the containsValue method.
     */
    static final int CONTAINS_VALUE_RETRIES = 3;

    /**
     * Number of cache access operations that can be buffered per segment before the cache's recency
     * ordering information is updated. This is used to avoid lock contention by recording a memento
     * of reads and delaying a lock acquisition until the threshold is crossed or a mutation occurs.
     *
     * <p>This must be a (2^n)-1 as it is used as a mask.
     */
    static final int DRAIN_THRESHOLD = 0x3F;

    /**
     * Maximum number of entries to be drained in a single cleanup run. This applies independently to
     * the cleanup queue and both reference queues.
     */
    static final int DRAIN_MAX = 16;

    // Fields

    /**
     * Mask value for indexing into segments. The upper bits of a key's hash code are used to choose
     * the segment.
     */
    final int segmentMask;

    /**
     * Shift value for indexing within segments. Helps prevent entries that end up in the same segment
     * from also ending up in the same bucket.
     */
    final int segmentShift;

    /**
     * The segments, each of which is a specialized hash table.
     */
    final ReactiveSegment<K, V>[] segments;

    /**
     * The concurrency level.
     */
    final int concurrencyLevel;

    /**
     * Strategy for comparing keys.
     */
    final Equivalence<Object> keyEquivalence;

    /**
     * Strategy for comparing values.
     */
    final Equivalence<Object> valueEquivalence;

    /**
     * Strategy for referencing keys.
     */
    final Strength keyStrength;

    /**
     * Strategy for referencing values.
     */
    final Strength valueStrength;

    /**
     * The maximum weight of this map. UNSET_INT if there is no maximum.
     */
    final long maxWeight;

    /**
     * Weigher to weigh cache entries.
     */
    final Weigher<K, V> weigher;

    /**
     * How long after the last access to an entry the map will retain that entry.
     */
    final long expireAfterAccessNanos;

    /**
     * How long after the last write to an entry the map will retain that entry.
     */
    final long expireAfterWriteNanos;

    /**
     * How long after the last write an entry becomes a candidate for refresh.
     */
    final long refreshNanos;

    /**
     * 等待超时时间
     */
    final long timeoutNanos;

    final Scheduler timeoutScheduler;

    final Scheduler loadingRestartScheduler;

    /**
     * Entries waiting to be consumed by the removal listener.
     */
    final Queue<RemovalNotification<K, V>> removalNotificationQueue;

    /**
     * A listener that is invoked when an entry is removed due to expiration or garbage collection of
     * soft/weak entries.
     */
    final RemovalListener<K, V> removalListener;

    /**
     * Measures time in a testable way.
     */
    final Ticker ticker;

    /**
     * Factory used to create new entries.
     */
    final EntryFactory entryFactory;

    /**
     * Accumulates global cache statistics. Note that there are also per-segments stats counters which
     * must be aggregated to obtain a global stats view.
     */
    final StatsCounter globalStatsCounter;

    /**
     * The default cache loader to use on loading operations.
     */

    final ReactiveCacheLoader<? super K, V> defaultLoader;

    /**
     * Creates a new, empty map with the specified strategy, initial capacity and concurrency level.
     */
    ReactiveLocalCache(ReactiveCacheBuilder<? super K, ? super V> builder, ReactiveCacheLoader<? super K, V> loader) {
        concurrencyLevel = Math.min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

        keyStrength = builder.getKeyStrength();
        valueStrength = builder.getValueStrength();

        keyEquivalence = builder.getKeyEquivalence();
        valueEquivalence = builder.getValueEquivalence();

        maxWeight = builder.getMaximumWeight();
        weigher = builder.getWeigher();
        expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
        expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
        refreshNanos = builder.getRefreshNanos();
        timeoutNanos = builder.getTimeoutNanos();

        timeoutScheduler = builder.getTimeoutScheduler();
        loadingRestartScheduler = builder.getLoadingRestartScheduler();

        removalListener = builder.getRemovalListener();
        removalNotificationQueue =
                (removalListener == ReactiveCacheBuilder.NullListener.INSTANCE)
                        ? ReactiveLocalCache.discardingQueue()
                        : new ConcurrentLinkedQueue<>();

        ticker = builder.getTicker(recordsTime());
        entryFactory = EntryFactory.getFactory(keyStrength, usesAccessEntries(), usesWriteEntries());
        globalStatsCounter = builder.getStatsCounterSupplier().get();
        defaultLoader = loader;

        int initialCapacity = Math.min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
        if (evictsBySize() && !customWeigher()) {
            initialCapacity = Math.min(initialCapacity, (int) maxWeight);
        }

        // Find the lowest power-of-two segmentCount that exceeds concurrencyLevel, unless
        // maximumSize/Weight is specified in which case ensure that each segment gets at least 10
        // entries. The special casing for size-based eviction is only necessary because that eviction
        // happens per segment instead of globally, so too many segments compared to the maximum size
        // will result in random eviction behavior.
        int segmentShift = 0;
        int segmentCount = 1;
        while (segmentCount < concurrencyLevel && (!evictsBySize() || segmentCount * 20 <= maxWeight)) {
            ++segmentShift;
            segmentCount <<= 1;
        }
        this.segmentShift = 32 - segmentShift;
        segmentMask = segmentCount - 1;

        this.segments = newSegmentArray(segmentCount);

        int segmentCapacity = initialCapacity / segmentCount;
        if (segmentCapacity * segmentCount < initialCapacity) {
            ++segmentCapacity;
        }

        int segmentSize = 1;
        while (segmentSize < segmentCapacity) {
            segmentSize <<= 1;
        }

        if (evictsBySize()) {
            // Ensure sum of segment max weights = overall max weights
            long maxSegmentWeight = maxWeight / segmentCount + 1;
            long remainder = maxWeight % segmentCount;
            for (int i = 0; i < this.segments.length; ++i) {
                if (i == remainder) {
                    maxSegmentWeight--;
                }
                this.segments[i] =
                        createSegment(segmentSize, maxSegmentWeight, builder.getStatsCounterSupplier().get());
            }
        } else {
            for (int i = 0; i < this.segments.length; ++i) {
                this.segments[i] =
                        createSegment(segmentSize, UNSET_INT, builder.getStatsCounterSupplier().get());
            }
        }
    }

    public boolean evictsBySize() {
        return maxWeight >= 0;
    }

    public boolean customWeigher() {
        return weigher != ReactiveCacheBuilder.OneWeigher.INSTANCE;
    }

    public boolean expires() {
        return expiresAfterWrite() || expiresAfterAccess();
    }

    public boolean expiresAfterWrite() {
        return expireAfterWriteNanos > 0;
    }

    public boolean expiresAfterAccess() {
        return expireAfterAccessNanos > 0;
    }

    public boolean refreshes() {
        return refreshNanos > 0;
    }

    public boolean usesAccessQueue() {
        return expiresAfterAccess() || evictsBySize();
    }

    public boolean usesWriteQueue() {
        return expiresAfterWrite();
    }

    public boolean recordsWrite() {
        return expiresAfterWrite() || refreshes();
    }

    public boolean recordsAccess() {
        return expiresAfterAccess();
    }

    public boolean recordsTime() {
        return recordsWrite() || recordsAccess();
    }

    public boolean usesWriteEntries() {
        return usesWriteQueue() || recordsWrite();
    }

    public boolean usesAccessEntries() {
        return usesAccessQueue() || recordsAccess();
    }

    public boolean usesKeyReferences() {
        return keyStrength != Strength.STRONG;
    }

    public boolean usesValueReferences() {
        return valueStrength != Strength.STRONG;
    }

    /**
     * Placeholder. Indicates that the value hasn't been set yet.
     */
    static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
        @Override
        public Object get() {
            return null;
        }

        @Override
        public void waitForValue(MonoSink<Object> sink, Scheduler scheduler) {
            sink.success(null);
        }

        @Override
        public int getWeight() {
            return 0;
        }

        @Override
        public ReferenceEntry<Object, Object> getEntry() {
            return null;
        }

        @Override
        public ValueReference<Object, Object> copyFor(
                ReferenceQueue<Object> queue,
                Object value,
                ReferenceEntry<Object, Object> entry) {
            return this;
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void notifyNewValue(Object newValue) {
        }
    };

    /**
     * Singleton placeholder that indicates a value is being loaded.
     */
    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    public static <K, V> ValueReference<K, V> unset() {
        return (ValueReference<K, V>) UNSET;
    }

    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    public static <K, V> ReferenceEntry<K, V> nullEntry() {
        return (ReferenceEntry<K, V>) NullEntry.INSTANCE;
    }

    static final Queue<?> DISCARDING_QUEUE = new AbstractQueue<Object>() {
        @Override
        public boolean offer(Object o) {
            return true;
        }

        @Override
        public Object peek() {
            return null;
        }

        @Override
        public Object poll() {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Iterator<Object> iterator() {
            return Collections.emptyIterator();
        }
    };

    /**
     * Queue that discards all elements.
     */
    @SuppressWarnings("unchecked") // impl never uses a parameter or returns any non-null value
    static <E> Queue<E> discardingQueue() {
        return (Queue<E>) DISCARDING_QUEUE;
    }

    /**
     * Applies a supplemental hash function to a given hash code, which defends against poor quality
     * hash functions. This is critical when the concurrent hash map uses power-of-two length hash
     * tables, that otherwise encounter collisions for hash codes that do not differ in lower or upper
     * bits.
     *
     * @param h hash code
     */
    static int rehash(int h) {
        // Spread bits to regularize both segment and index locations,
        // using variant of single-word Wang/Jenkins hash.
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        return h ^ (h >>> 16);
    }

    int hash(Object key) {
        int h = keyEquivalence.hash(key);
        return rehash(h);
    }

    void reclaimValue(ValueReference<K, V> valueReference) {
        ReferenceEntry<K, V> entry = valueReference.getEntry();
        int hash = entry.getHash();
        segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference).subscribe();
    }

    void reclaimKey(ReferenceEntry<K, V> entry) {
        int hash = entry.getHash();
        segmentFor(hash).reclaimKey(entry, hash).subscribe();
    }

    /**
     * This method is a convenience for testing. Code should call {@link ReactiveSegment#getLiveValue}
     * instead.
     */
    boolean isLive(ReferenceEntry<K, V> entry, long now) {
        return segmentFor(entry.getHash()).getLiveValue(entry, now) != null;
    }

    /**
     * Returns the segment that should be used for a key with the given hash.
     *
     * @param hash the hash code for the key
     * @return the segment
     */
    ReactiveSegment<K, V> segmentFor(int hash) {
        return segments[(hash >>> segmentShift) & segmentMask];
    }

    ReactiveSegment<K, V> createSegment(
            int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
        return new ReactiveSegment<>(this, initialCapacity, maxSegmentWeight, statsCounter);
    }

    /**
     * Gets the value from an entry. Returns null if the entry is invalid, partially-collected,
     * loading, or expired. Unlike {@link ReactiveSegment#getLiveValue} this method does not attempt to
     * clean up stale entries. As such it should only be called outside a segment context, such as
     * during iteration.
     */
    V getLiveValue(ReferenceEntry<K, V> entry, long now) {
        if (entry.getKey() == null) {
            return null;
        }
        V value = entry.getValueReference().get();
        if (value == null) {
            return null;
        }

        if (isExpired(entry, now)) {
            return null;
        }
        return value;
    }

    // expiration

    /**
     * Returns true if the entry has expired.
     */
    boolean isExpired(ReferenceEntry<K, V> entry, long now) {
        Objects.requireNonNull(entry);
        if (expiresAfterAccess() && (now - entry.getAccessTime() >= expireAfterAccessNanos)) {
            return true;
        }
        if (expiresAfterWrite() && (now - entry.getWriteTime() >= expireAfterWriteNanos)) {
            return true;
        }
        return false;
    }


    public static <K, V> void connectAccessOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInAccessQueue(next);
        next.setPreviousInAccessQueue(previous);
    }

    public static <K, V> void nullifyAccessOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInAccessQueue(nullEntry);
        nulled.setPreviousInAccessQueue(nullEntry);
    }

    public static <K, V> void connectWriteOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInWriteQueue(next);
        next.setPreviousInWriteQueue(previous);
    }

    public static <K, V> void nullifyWriteOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInWriteQueue(nullEntry);
        nulled.setPreviousInWriteQueue(nullEntry);
    }

    /**
     * Notifies listeners that an entry has been automatically removed due to expiration, eviction, or
     * eligibility for garbage collection. This should be called every time expireEntries or
     * evictEntry is called (once the lock is released).
     */
    void processPendingNotifications() {
        RemovalNotification<K, V> notification;
        while ((notification = removalNotificationQueue.poll()) != null) {
            try {
                removalListener.onRemoval(notification);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Exception thrown by removal listener", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    ReactiveSegment<K, V>[] newSegmentArray(int ssize) {
        return new ReactiveSegment[ssize];
    }

    // Cache support

    public void cleanUp() {
        for (ReactiveSegment<?, ?> segment : segments) {
            segment.cleanUp();
        }
    }

    // ConcurrentMap methods

    @Override
    public boolean isEmpty() {
        /*
         * Sum per-segment modCounts to avoid mis-reporting when elements are concurrently added and
         * removed in one segment while checking another, in which case the table was never actually
         * empty at any point. (The sum ensures accuracy up through at least 1<<31 per-segment
         * modifications before recheck.) Method containsValue() uses similar constructions for
         * stability checks.
         */
        long sum = 0L;
        ReactiveSegment<K, V>[] segments = this.segments;
        for (ReactiveSegment<K, V> kvSegment : segments) {
            if (kvSegment.count != 0) {
                return false;
            }
            sum += kvSegment.modCount;
        }

        if (sum != 0L) { // recheck unless no modifications
            for (ReactiveSegment<K, V> segment : segments) {
                if (segment.count != 0) {
                    return false;
                }
                sum -= segment.modCount;
            }
            if (sum != 0L) {
                return false;
            }
        }
        return true;
    }

    long longSize() {
        ReactiveSegment<K, V>[] segments = this.segments;
        long sum = 0;
        for (ReactiveSegment<K, V> segment : segments) {
            sum += Math.max(0, segment.count);
        }
        return sum;
    }

    @Override
    public int size() {
        return (int) longSize();
    }

    @Override
    public Mono<V> get(Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).get(key, hash);
    }

    public Mono<V> getIfPresent(K key) {
        int hash = hash(Objects.requireNonNull(key));
        return segmentFor(hash).get(key, hash)
                .doOnNext(v -> globalStatsCounter.recordHits(1))
                .switchIfEmpty(Mono.fromRunnable(() -> globalStatsCounter.recordMisses(1)));
    }

    // Only becomes available in Java 8 when it's on the interface.
    // @Override

    public Mono<V> getOrDefault(Object key, Mono<V> defaultValue) {
        return get(key).switchIfEmpty(defaultValue);
    }

    Mono<V> get(K key, ReactiveCacheLoader<? super K, V> loader) {
        int hash = hash(Objects.requireNonNull(key));
        return segmentFor(hash).get(key, hash, loader);
    }

    Mono<V> getOrLoad(K key) {
        return get(key, defaultLoader);
    }

    Flux<Tuple2<K, V>> getAllPresent(Iterable<? extends K> keys) {
        return Flux.fromIterable(keys)
                .flatMap(k -> get(k)
                        .switchIfEmpty(Mono.fromRunnable(() ->
                                globalStatsCounter.recordMisses(1)
                        )).doOnNext(v ->
                                globalStatsCounter.recordHits(1)
                        ).flatMap(v ->
                                Mono.just(Tuples.of(k, v))
                        )
                );
    }

    /**
     * Returns the internal entry for the specified key. The entry may be loading, expired, or
     * partially collected.
     */
    ReferenceEntry<K, V> getEntry(Object key) {
        // does not impact recency ordering
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).getEntry(key, hash);
    }

    void refresh(K key) {
        int hash = hash(Objects.requireNonNull(key));
        segmentFor(hash).refresh(key, hash, defaultLoader, false);
    }

    @Override
    public boolean containsKey(Object key) {
        // does not impact recency ordering
        if (key == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).containsKey(key, hash);
    }

    @Override
    public boolean containsValue(Object value) {
        // does not impact recency ordering
        if (value == null) {
            return false;
        }

        // This implementation is patterned after ConcurrentHashMap, but without the locking. The only
        // way for it to return a false negative would be for the target value to jump around in the map
        // such that none of the subsequent iterations observed it, despite the fact that at every point
        // in time it was present somewhere int the map. This becomes increasingly unlikely as
        // CONTAINS_VALUE_RETRIES increases, though without locking it is theoretically possible.
        long now = ticker.read();
        final ReactiveSegment<K, V>[] segments = this.segments;
        long last = -1L;
        for (int i = 0; i < CONTAINS_VALUE_RETRIES; i++) {
            long sum = 0L;
            for (ReactiveSegment<K, V> segment : segments) {
                // ensure visibility of most recent completed write
                @SuppressWarnings("unused") int unused = segment.count; // read-volatile

                AtomicReferenceArray<ReferenceEntry<K, V>> table = segment.table;
                for (int j = 0; j < table.length(); j++) {
                    for (ReferenceEntry<K, V> e = table.get(j); e != null; e = e.getNext()) {
                        V v = segment.getLiveValue(e, now);
                        if (v != null && valueEquivalence.equivalent(value, v)) {
                            return true;
                        }
                    }
                }
                sum += segment.modCount;
            }
            if (sum == last) {
                break;
            }
            last = sum;
        }
        return false;
    }

    @Override
    public Mono<V> put(K key, Mono<V> value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        return value.switchIfEmpty(Mono.error(IllegalArgumentException::new))
                .flatMap(v -> {
                    int hash = hash(key);
                    return segmentFor(hash).put(key, hash, v, false);
                });
    }

    @Override
    public Mono<V> putIfAbsent(K key, Mono<V> value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        return value.switchIfEmpty(Mono.error(IllegalArgumentException::new))
                .flatMap(v -> {
                    int hash = hash(key);
                    return segmentFor(hash).put(key, hash, v, true);
                });
    }

    @Override
    public void putAll(Map<? extends K, ? extends Mono<V>> m) {
        for (Entry<? extends K, ? extends Mono<V>> e : m.entrySet()) {
            put(e.getKey(), e.getValue()).subscribe();
        }
    }

    @Override
    public Mono<V> remove(Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash);
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (key == null || value == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash, value).block();
    }

    @Override
    public boolean replace(K key, Mono<V> oldValue, Mono<V> newValue) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(newValue);
        if (oldValue == null) {
            return false;
        }

        Boolean block = oldValue
                .zipWith(newValue)
                .flatMap(o -> {
                    int hash = hash(key);
                    return segmentFor(hash).replace(key, hash, o.getT1(), o.getT2());
                }).switchIfEmpty(Mono.just(false)).block();

        return Boolean.TRUE.equals(block);
    }

    @Override
    public Mono<V> replace(K key, Mono<V> value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        return value.switchIfEmpty(Mono.error(IllegalArgumentException::new))
                .flatMap(v -> {
                    int hash = hash(key);
                    return segmentFor(hash).replace(key, hash, v);
                });
    }

    @Override
    public void clear() {
        for (ReactiveSegment<K, V> segment : segments) {
            segment.clear().block();
        }
    }

    void invalidateAll(Iterable<?> keys) {
        for (Object key : keys) {
            remove(key).subscribe();
        }
    }

    @Override
    public Set<K> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Mono<V>> values() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Entry<K, Mono<V>>> entrySet() {
        throw new UnsupportedOperationException();
    }

}
