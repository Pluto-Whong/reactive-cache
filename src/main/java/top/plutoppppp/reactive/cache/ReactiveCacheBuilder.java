package top.plutoppppp.reactive.cache;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.plutoppppp.reactive.cache.common.Equivalence;
import top.plutoppppp.reactive.cache.common.MoreObjects;
import top.plutoppppp.reactive.cache.common.Ticker;
import top.plutoppppp.reactive.cache.listener.RemovalCause;
import top.plutoppppp.reactive.cache.listener.RemovalListener;
import top.plutoppppp.reactive.cache.listener.RemovalNotification;
import top.plutoppppp.reactive.cache.stats.CacheStats;
import top.plutoppppp.reactive.cache.stats.DisabledStatsCounter;
import top.plutoppppp.reactive.cache.stats.SimpleStatsCounter;
import top.plutoppppp.reactive.cache.stats.StatsCounter;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static top.plutoppppp.reactive.cache.common.Assert.checkArgument;
import static top.plutoppppp.reactive.cache.common.Assert.checkState;

public final class ReactiveCacheBuilder<K, V> {

    private static final Logger logger = Logger.getLogger(ReactiveCacheBuilder.class.getName());

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int DEFAULT_CONCURRENCY_LEVEL = 4;
    private static final int DEFAULT_EXPIRATION_NANOS = 0;
    private static final int DEFAULT_REFRESH_NANOS = 0;
    private static final long DEFAULT_TIMEOUT_NANOS = 0L;

    static final Supplier<? extends StatsCounter> NULL_STATS_COUNTER = () -> DisabledStatsCounter.INSTANCE;
    static final Supplier<StatsCounter> CACHE_STATS_COUNTER = SimpleStatsCounter::new;

    enum NullListener implements RemovalListener<Object, Object> {
        INSTANCE;

        @Override
        public void onRemoval(RemovalNotification<Object, Object> notification) {
        }
    }

    enum OneWeigher implements Weigher<Object, Object> {
        INSTANCE;

        @Override
        public int weigh(Object key, Object value) {
            return 1;
        }
    }

    static final Ticker NULL_TICKER = new Ticker() {
        @Override
        public long read() {
            return 0;
        }
    };

    static final Scheduler DEFAULT_TIMEOUT_SCHEDULER = Schedulers.parallel();
    static final Scheduler DEFAULT_LOADING_RESTART_SCHEDULER = Schedulers.parallel();

    public static final int UNSET_INT = -1;

    boolean strictParsing = true;

    int initialCapacity = UNSET_INT;
    int concurrencyLevel = UNSET_INT;
    long maximumSize = UNSET_INT;
    long maximumWeight = UNSET_INT;
    Weigher<? super K, ? super V> weigher;

    Strength keyStrength;
    Strength valueStrength;

    long expireAfterWriteNanos = UNSET_INT;
    long expireAfterAccessNanos = UNSET_INT;
    long refreshNanos = UNSET_INT;
    long timeoutNanos = UNSET_INT;

    Scheduler timeoutScheduler;
    Scheduler loadingRestartScheduler;

    Equivalence<Object> keyEquivalence;
    Equivalence<Object> valueEquivalence;

    RemovalListener<? super K, ? super V> removalListener;
    Ticker ticker;

    Supplier<? extends StatsCounter> statsCounterSupplier = NULL_STATS_COUNTER;

    private ReactiveCacheBuilder() {
    }

    /**
     * Constructs a new {@code CacheBuilder} instance with default settings, including strong keys,
     * strong values, and no automatic eviction of any kind.
     */
    public static ReactiveCacheBuilder<Object, Object> newBuilder() {
        return new ReactiveCacheBuilder<>();
    }

    /**
     * Enables lenient parsing. Useful for tests and spec parsing.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     */
    // To be supported
    ReactiveCacheBuilder<K, V> lenientParsing() {
        strictParsing = false;
        return this;
    }

    /**
     * Sets a custom {@code Equivalence} strategy for comparing keys.
     *
     * <p>By default, the cache uses {@link Equivalence#identity} to determine key equality when
     * {@link #weakKeys} is specified, and {@link Equivalence#equals()} otherwise.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     */
    // To be supported
    ReactiveCacheBuilder<K, V> keyEquivalence(Equivalence<Object> equivalence) {
        checkState(keyEquivalence == null, "key equivalence was already set to %s", keyEquivalence);
        keyEquivalence = Objects.requireNonNull(equivalence);
        return this;
    }

    public Equivalence<Object> getKeyEquivalence() {
        return MoreObjects.firstNonNull(keyEquivalence, getKeyStrength().defaultEquivalence());
    }

    /**
     * Sets a custom {@code Equivalence} strategy for comparing values.
     *
     * <p>By default, the cache uses {@link Equivalence#identity} to determine value equality when
     * {@link #weakValues} or {@link #softValues} is specified, and {@link Equivalence#equals()}
     * otherwise.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     */
    // To be supported
    ReactiveCacheBuilder<K, V> valueEquivalence(Equivalence<Object> equivalence) {
        checkState(
                valueEquivalence == null, "value equivalence was already set to %s", valueEquivalence);
        this.valueEquivalence = Objects.requireNonNull(equivalence);
        return this;
    }

    public Equivalence<Object> getValueEquivalence() {
        return MoreObjects.firstNonNull(valueEquivalence, getValueStrength().defaultEquivalence());
    }

    /**
     * Sets the minimum total size for the internal hash tables. For example, if the initial capacity
     * is {@code 60}, and the concurrency level is {@code 8}, then eight segments are created, each
     * having a hash table of size eight. Providing a large enough estimate at construction time
     * avoids the need for expensive resizing operations later, but setting this value unnecessarily
     * high wastes memory.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code initialCapacity} is negative
     * @throws IllegalStateException    if an initial capacity was already set
     */
    public ReactiveCacheBuilder<K, V> initialCapacity(int initialCapacity) {
        checkState(
                this.initialCapacity == UNSET_INT,
                "initial capacity was already set to %s",
                this.initialCapacity);
        checkArgument(initialCapacity >= 0);
        this.initialCapacity = initialCapacity;
        return this;
    }

    public int getInitialCapacity() {
        return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
    }

    /**
     * Guides the allowed concurrency among update operations. Used as a hint for internal sizing. The
     * table is internally partitioned to try to permit the indicated number of concurrent updates
     * without contention. Because assignment of entries to these partitions is not necessarily
     * uniform, the actual concurrency observed may vary. Ideally, you should choose a value to
     * accommodate as many threads as will ever concurrently modify the table. Using a significantly
     * higher value than you need can waste space and time, and a significantly lower value can lead
     * to thread contention. But overestimates and underestimates within an order of magnitude do not
     * usually have much noticeable impact. A value of one permits only one thread to modify the cache
     * at a time, but since read operations and cache loading computations can proceed concurrently,
     * this still yields higher concurrency than full synchronization.
     *
     * <p>Defaults to 4. <b>Note:</b>The default may change in the future. If you care about this
     * value, you should always choose it explicitly.
     *
     * <p>The current implementation uses the concurrency level to create a fixed number of hashtable
     * segments, each governed by its own write lock. The segment lock is taken once for each explicit
     * write, and twice for each cache loading computation (once prior to loading the new value, and
     * once after loading completes). Much internal cache management is performed at the segment
     * granularity. For example, access queues and write queues are kept per segment when they are
     * required by the selected eviction algorithm. As such, when writing unit tests it is not
     * uncommon to specify {@code concurrencyLevel(1)} in order to achieve more deterministic eviction
     * behavior.
     *
     * <p>Note that future implementations may abandon segment locking in favor of more advanced
     * concurrency controls.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code concurrencyLevel} is nonpositive
     * @throws IllegalStateException    if a concurrency level was already set
     */
    public ReactiveCacheBuilder<K, V> concurrencyLevel(int concurrencyLevel) {
        checkState(
                this.concurrencyLevel == UNSET_INT,
                "concurrency level was already set to %s",
                this.concurrencyLevel);
        checkArgument(concurrencyLevel > 0);
        this.concurrencyLevel = concurrencyLevel;
        return this;
    }

    public int getConcurrencyLevel() {
        return (concurrencyLevel == UNSET_INT) ? DEFAULT_CONCURRENCY_LEVEL : concurrencyLevel;
    }

    /**
     * Specifies the maximum number of entries the cache may contain. Note that the cache <b>may evict
     * an entry before this limit is exceeded</b>. As the cache size grows close to the maximum, the
     * cache evicts entries that are less likely to be used again. For example, the cache may evict an
     * entry because it hasn't been used recently or very often.
     *
     * <p>When {@code size} is zero, elements will be evicted immediately after being loaded into the
     * cache. This can be useful in testing, or to disable caching temporarily without a code change.
     *
     * <p>This feature cannot be used in conjunction with {@link #maximumWeight}.
     *
     * @param size the maximum size of the cache
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code size} is negative
     * @throws IllegalStateException    if a maximum size or weight was already set
     */
    public ReactiveCacheBuilder<K, V> maximumSize(long size) {
        checkState(
                this.maximumSize == UNSET_INT, "maximum size was already set to %s", this.maximumSize);
        checkState(
                this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s",
                this.maximumWeight);
        checkState(this.weigher == null, "maximum size can not be combined with weigher");
        checkArgument(size >= 0, "maximum size must not be negative");
        this.maximumSize = size;
        return this;
    }

    /**
     * Specifies the maximum weight of entries the cache may contain. Weight is determined using the
     * {@link Weigher} specified with {@link #weigher}, and use of this method requires a
     * corresponding call to {@link #weigher} prior to calling {@link #build}.
     *
     * <p>Note that the cache <b>may evict an entry before this limit is exceeded</b>. As the cache
     * size grows close to the maximum, the cache evicts entries that are less likely to be used
     * again. For example, the cache may evict an entry because it hasn't been used recently or very
     * often.
     *
     * <p>When {@code weight} is zero, elements will be evicted immediately after being loaded into
     * cache. This can be useful in testing, or to disable caching temporarily without a code change.
     *
     * <p>Note that weight is only used to determine whether the cache is over capacity; it has no
     * effect on selecting which entry should be evicted next.
     *
     * <p>This feature cannot be used in conjunction with {@link #maximumSize}.
     *
     * @param weight the maximum total weight of entries the cache may contain
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code weight} is negative
     * @throws IllegalStateException    if a maximum weight or size was already set
     * @since 11.0
     */
    // To be supported
    public ReactiveCacheBuilder<K, V> maximumWeight(long weight) {
        checkState(
                this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s",
                this.maximumWeight);
        checkState(
                this.maximumSize == UNSET_INT, "maximum size was already set to %s", this.maximumSize);
        this.maximumWeight = weight;
        checkArgument(weight >= 0, "maximum weight must not be negative");
        return this;
    }

    /**
     * Specifies the weigher to use in determining the weight of entries. Entry weight is taken into
     * consideration by {@link #maximumWeight(long)} when determining which entries to evict, and use
     * of this method requires a corresponding call to {@link #maximumWeight(long)} prior to calling
     * {@link #build}. Weights are measured and recorded when entries are inserted into the cache, and
     * are thus effectively static during the lifetime of a cache entry.
     *
     * <p>When the weight of an entry is zero it will not be considered for size-based eviction
     * (though it still may be evicted by other means).
     *
     * <p><b>Important note:</b> Instead of returning <em>this</em> as a {@code CacheBuilder}
     * instance, this method returns {@code CacheBuilder<K1, V1>}. From this point on, either the
     * original reference or the returned reference may be used to complete configuration and build
     * the cache, but only the "generic" one is type-safe. That is, it will properly prevent you from
     * building caches whose key or value types are incompatible with the types accepted by the
     * weigher already provided; the {@code CacheBuilder} type cannot do this. For best results,
     * simply use the standard method-chaining idiom, as illustrated in the documentation at top,
     * configuring a {@code CacheBuilder} and building your {@link ReactiveCache} all in a single statement.
     *
     * <p><b>Warning:</b> if you ignore the above advice, and use this {@code CacheBuilder} to build a
     * cache whose key or value type is incompatible with the weigher, you will likely experience a
     * {@link ClassCastException} at some <i>undefined</i> point in the future.
     *
     * @param weigher the weigher to use in calculating the weight of cache entries
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code size} is negative
     * @throws IllegalStateException    if a maximum size was already set
     * @since 11.0
     */
    // To be supported
    public <K1 extends K, V1 extends V> ReactiveCacheBuilder<K1, V1> weigher(
            Weigher<? super K1, ? super V1> weigher) {
        checkState(this.weigher == null);
        if (strictParsing) {
            checkState(
                    this.maximumSize == UNSET_INT,
                    "weigher can not be combined with maximum size: %s",
                    this.maximumSize);
        }

        // safely limiting the kinds of caches this can produce
        @SuppressWarnings("unchecked")
        ReactiveCacheBuilder<K1, V1> me = (ReactiveCacheBuilder<K1, V1>) this;
        me.weigher = Objects.requireNonNull(weigher);
        return me;
    }

    public long getMaximumWeight() {
        if (expireAfterWriteNanos == 0 || expireAfterAccessNanos == 0) {
            return 0;
        }
        return (weigher == null) ? maximumSize : maximumWeight;
    }

    // Make a safe contravariant cast now so we don't have to do it over and over.
    @SuppressWarnings("unchecked")
    public <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher() {
        return (Weigher<K1, V1>) MoreObjects.firstNonNull(weigher, OneWeigher.INSTANCE);
    }

    /**
     * Specifies that each key (not value) stored in the cache should be wrapped in a
     * {@link WeakReference} (by default, strong references are used).
     *
     * <p><b>Warning:</b> when this method is used, the resulting cache will use identity ({@code ==})
     * comparison to determine equality of keys.
     *
     * <p>Entries with keys that have been garbage collected may be counted in {@link ReactiveCache#size}, but
     * will never be visible to read or write operations; such entries are cleaned up as part of the
     * routine maintenance described in the class javadoc.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException if the key strength was already set
     */
    // java.lang.ref.WeakReference
    public ReactiveCacheBuilder<K, V> weakKeys() {
        return setKeyStrength(Strength.WEAK);
    }

    ReactiveCacheBuilder<K, V> setKeyStrength(Strength strength) {
        checkState(keyStrength == null, "Key strength was already set to %s", keyStrength);
        keyStrength = Objects.requireNonNull(strength);
        return this;
    }

    Strength getKeyStrength() {
        return MoreObjects.firstNonNull(keyStrength, Strength.STRONG);
    }

    /**
     * Specifies that each value (not key) stored in the cache should be wrapped in a
     * {@link WeakReference} (by default, strong references are used).
     *
     * <p>Weak values will be garbage collected once they are weakly reachable. This makes them a poor
     * candidate for caching; consider {@link #softValues} instead.
     *
     * <p><b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
     * comparison to determine equality of values.
     *
     * <p>Entries with values that have been garbage collected may be counted in {@link ReactiveCache#size},
     * but will never be visible to read or write operations; such entries are cleaned up as part of
     * the routine maintenance described in the class javadoc.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException if the value strength was already set
     */
    // java.lang.ref.WeakReference
    public ReactiveCacheBuilder<K, V> weakValues() {
        return setValueStrength(Strength.WEAK);
    }

    /**
     * Specifies that each value (not key) stored in the cache should be wrapped in a
     * {@link SoftReference} (by default, strong references are used). Softly-referenced objects will
     * be garbage-collected in a <i>globally</i> least-recently-used manner, in response to memory
     * demand.
     *
     * <p><b>Warning:</b> in most circumstances it is better to set a per-cache
     * {@linkplain #maximumSize(long) maximum size} instead of using soft references. You should only
     * use this method if you are well familiar with the practical consequences of soft references.
     *
     * <p><b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
     * comparison to determine equality of values.
     *
     * <p>Entries with values that have been garbage collected may be counted in {@link ReactiveCache#size},
     * but will never be visible to read or write operations; such entries are cleaned up as part of
     * the routine maintenance described in the class javadoc.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException if the value strength was already set
     */
    // java.lang.ref.SoftReference
    public ReactiveCacheBuilder<K, V> softValues() {
        return setValueStrength(Strength.SOFT);
    }

    ReactiveCacheBuilder<K, V> setValueStrength(Strength strength) {
        checkState(valueStrength == null, "Value strength was already set to %s", valueStrength);
        valueStrength = Objects.requireNonNull(strength);
        return this;
    }

    Strength getValueStrength() {
        return MoreObjects.firstNonNull(valueStrength, Strength.STRONG);
    }

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed duration
     * has elapsed after the entry's creation, or the most recent replacement of its value.
     *
     * <p>When {@code duration} is zero, this method hands off to {@link #maximumSize(long)
     * maximumSize}{@code (0)}, ignoring any otherwise-specificed maximum size or weight. This can be
     * useful in testing, or to disable caching temporarily without a code change.
     *
     * <p>Expired entries may be counted in {@link ReactiveCache#size}, but will never be visible to read or
     * write operations. Expired entries are cleaned up as part of the routine maintenance described
     * in the class javadoc.
     *
     * @param duration the length of time after an entry is created that it should be automatically
     *                 removed
     * @param unit     the unit that {@code duration} is expressed in
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws IllegalStateException    if the time to live or time to idle was already set
     */
    public ReactiveCacheBuilder<K, V> expireAfterWrite(long duration, TimeUnit unit) {
        checkState(
                expireAfterWriteNanos == UNSET_INT,
                "expireAfterWrite was already set to %s ns",
                expireAfterWriteNanos);
        checkArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterWriteNanos = unit.toNanos(duration);
        return this;
    }

    public long getExpireAfterWriteNanos() {
        return (expireAfterWriteNanos == UNSET_INT) ? DEFAULT_EXPIRATION_NANOS : expireAfterWriteNanos;
    }

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed duration
     * has elapsed after the entry's creation, the most recent replacement of its value, or its last
     * access. Access time is reset by all cache read and write operations (including
     * {@code Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations
     * on the collection-views of {@link ReactiveCache#asMap}.
     *
     * <p>When {@code duration} is zero, this method hands off to {@link #maximumSize(long)
     * maximumSize}{@code (0)}, ignoring any otherwise-specificed maximum size or weight. This can be
     * useful in testing, or to disable caching temporarily without a code change.
     *
     * <p>Expired entries may be counted in {@link ReactiveCache#size}, but will never be visible to read or
     * write operations. Expired entries are cleaned up as part of the routine maintenance described
     * in the class javadoc.
     *
     * @param duration the length of time after an entry is last accessed that it should be
     *                 automatically removed
     * @param unit     the unit that {@code duration} is expressed in
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws IllegalStateException    if the time to idle or time to live was already set
     */
    public ReactiveCacheBuilder<K, V> expireAfterAccess(long duration, TimeUnit unit) {
        checkState(
                expireAfterAccessNanos == UNSET_INT,
                "expireAfterAccess was already set to %s ns",
                expireAfterAccessNanos);
        checkArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterAccessNanos = unit.toNanos(duration);
        return this;
    }

    public long getExpireAfterAccessNanos() {
        return (expireAfterAccessNanos == UNSET_INT)
                ? DEFAULT_EXPIRATION_NANOS
                : expireAfterAccessNanos;
    }

    // To be supported (synchronously).
    public ReactiveCacheBuilder<K, V> refreshAfterWrite(long duration, TimeUnit unit) {
        Objects.requireNonNull(unit);
        checkState(refreshNanos == UNSET_INT, "refresh was already set to %s ns", refreshNanos);
        checkArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
        this.refreshNanos = unit.toNanos(duration);
        return this;
    }

    public long getRefreshNanos() {
        return (refreshNanos == UNSET_INT) ? DEFAULT_REFRESH_NANOS : refreshNanos;
    }

    /**
     * 等待超时时间，尽可能的避免一些线程异常导致的链条永久中断的可能
     *
     * @param duration
     * @param unit
     * @return
     */
    public ReactiveCacheBuilder<K, V> loadingTimeout(long duration, TimeUnit unit) {
        Objects.requireNonNull(unit);
        checkState(timeoutNanos == UNSET_INT, "refresh was already set to %s ns", timeoutNanos);
        checkArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
        this.timeoutNanos = unit.toNanos(duration);
        return this;
    }

    public long getTimeoutNanos() {
        return (timeoutNanos == UNSET_INT) ? DEFAULT_TIMEOUT_NANOS : timeoutNanos;
    }

    /**
     * 超时时另唤起的线程
     *
     * @param scheduler
     * @return
     */
    public ReactiveCacheBuilder<K, V> timeoutScheduler(Scheduler scheduler) {
        Objects.requireNonNull(scheduler);
        checkState(Objects.isNull(timeoutScheduler), "timeout scheduler was already set");
        this.timeoutScheduler = scheduler;
        return this;
    }

    public Scheduler getTimeoutScheduler() {
        return Objects.isNull(timeoutScheduler) ? DEFAULT_TIMEOUT_SCHEDULER : timeoutScheduler;
    }

    /**
     * 加载完成后异步启动的线程
     *
     * @param scheduler
     * @return
     */
    public ReactiveCacheBuilder<K, V> loadingRestartScheduler(Scheduler scheduler) {
        Objects.requireNonNull(scheduler);
        checkState(Objects.isNull(loadingRestartScheduler), "loading restart scheduler was already set");
        this.loadingRestartScheduler = scheduler;
        return this;
    }

    public Scheduler getLoadingRestartScheduler() {
        return Objects.isNull(loadingRestartScheduler) ? DEFAULT_LOADING_RESTART_SCHEDULER : loadingRestartScheduler;
    }

    /**
     * Specifies a nanosecond-precision time source for this cache. By default,
     * {@link System#nanoTime} is used.
     *
     * <p>The primary intent of this method is to facilitate testing of caches with a fake or mock
     * time source.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException if a ticker was already set
     */
    public ReactiveCacheBuilder<K, V> ticker(Ticker ticker) {
        checkState(this.ticker == null);
        this.ticker = Objects.requireNonNull(ticker);
        return this;
    }

    public Ticker getTicker(boolean recordsTime) {
        if (ticker != null) {
            return ticker;
        }
        return recordsTime ? Ticker.systemTicker() : NULL_TICKER;
    }

    /**
     * Specifies a listener instance that caches should notify each time an entry is removed for any
     * {@linkplain RemovalCause reason}. Each cache created by this builder will invoke this listener
     * as part of the routine maintenance described in the class documentation above.
     *
     * <p><b>Warning:</b> after invoking this method, do not continue to use <i>this</i> cache builder
     * reference; instead use the reference this method <i>returns</i>. At runtime, these point to the
     * same instance, but only the returned reference has the correct generic type information so as
     * to ensure type safety. For best results, use the standard method-chaining idiom illustrated in
     * the class documentation above, configuring a builder and building your cache in a single
     * statement. Failure to heed this advice can result in a {@link ClassCastException} being thrown
     * by a cache operation at some <i>undefined</i> point in the future.
     *
     * <p><b>Warning:</b> any exception thrown by {@code listener} will <i>not</i> be propagated to
     * the {@code Cache} user, only logged via a {@link Logger}.
     *
     * @return the cache builder reference that should be used instead of {@code this} for any
     * remaining configuration and cache building
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException if a removal listener was already set
     */
    public <K1 extends K, V1 extends V> ReactiveCacheBuilder<K1, V1> removalListener(
            RemovalListener<? super K1, ? super V1> listener) {
        checkState(this.removalListener == null);

        // safely limiting the kinds of caches this can produce
        @SuppressWarnings("unchecked")
        ReactiveCacheBuilder<K1, V1> me = (ReactiveCacheBuilder<K1, V1>) this;
        me.removalListener = Objects.requireNonNull(listener);
        return me;
    }

    // Make a safe contravariant cast now so we don't have to do it over and over.
    @SuppressWarnings("unchecked")
    public <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
        return (RemovalListener<K1, V1>)
                MoreObjects.firstNonNull(removalListener, NullListener.INSTANCE);
    }

    /**
     * Enable the accumulation of {@link CacheStats} during the operation of the cache. Without this
     * {@link ReactiveCache#stats} will return zero for all statistics. Note that recording stats requires
     * bookkeeping to be performed with each operation, and thus imposes a performance penalty on
     * cache operation.
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @since 12.0 (previously, stats collection was automatic)
     */
    public ReactiveCacheBuilder<K, V> recordStats() {
        statsCounterSupplier = CACHE_STATS_COUNTER;
        return this;
    }

    boolean isRecordingStats() {
        return statsCounterSupplier == CACHE_STATS_COUNTER;
    }

    public Supplier<? extends StatsCounter> getStatsCounterSupplier() {
        return statsCounterSupplier;
    }

    /**
     * Builds a cache, which either returns an already-loaded value for a given key or atomically
     * computes or retrieves it using the supplied {@code CacheLoader}. If another thread is currently
     * loading the value for this key, simply waits for that thread to finish and returns its loaded
     * value. Note that multiple threads can concurrently load values for distinct keys.
     *
     * <p>This method does not alter the state of this {@code CacheBuilder} instance, so it can be
     * invoked again to create multiple independent caches.
     *
     * @param loader the cache loader used to obtain new values
     * @return a cache having the requested features
     */
    public <K1 extends K, V1 extends V> ReactiveLoadingCache<K1, V1> build(
            ReactiveCacheLoader<? super K1, V1> loader) {
        checkWeightWithWeigher();
        return new ReactiveLocalLoadingCache<>(this, loader);
    }

    public <K1 extends K, V1 extends V> ReactiveCache<K1, V1> build() {
        checkWeightWithWeigher();
        checkNonLoadingCache();
        return new ReactiveLocalManualCache<>(this);
    }

    private void checkNonLoadingCache() {
        checkState(refreshNanos == UNSET_INT, "refreshAfterWrite requires a LoadingCache");
    }

    private void checkWeightWithWeigher() {
        if (weigher == null) {
            checkState(maximumWeight == UNSET_INT, "maximumWeight requires weigher");
        } else {
            if (strictParsing) {
                checkState(maximumWeight != UNSET_INT, "weigher requires maximumWeight");
            } else {
                if (maximumWeight == UNSET_INT) {
                    logger.log(Level.WARNING, "ignoring weigher specified without maximumWeight");
                }
            }
        }
    }

    /**
     * Returns a string representation for this CacheBuilder instance. The exact form of the returned
     * string is not specified.
     */
    @Override
    public String toString() {
        MoreObjects.ToStringHelper s = MoreObjects.toStringHelper(this);
        if (initialCapacity != UNSET_INT) {
            s.add("initialCapacity", initialCapacity);
        }
        if (concurrencyLevel != UNSET_INT) {
            s.add("concurrencyLevel", concurrencyLevel);
        }
        if (maximumSize != UNSET_INT) {
            s.add("maximumSize", maximumSize);
        }
        if (maximumWeight != UNSET_INT) {
            s.add("maximumWeight", maximumWeight);
        }
        if (expireAfterWriteNanos != UNSET_INT) {
            s.add("expireAfterWrite", expireAfterWriteNanos + "ns");
        }
        if (expireAfterAccessNanos != UNSET_INT) {
            s.add("expireAfterAccess", expireAfterAccessNanos + "ns");
        }
        if (keyStrength != null) {
            s.add("keyStrength", keyStrength.toString().toLowerCase());
        }
        if (valueStrength != null) {
            s.add("valueStrength", valueStrength.toString().toLowerCase());
        }
        if (keyEquivalence != null) {
            s.addValue("keyEquivalence");
        }
        if (valueEquivalence != null) {
            s.addValue("valueEquivalence");
        }
        if (removalListener != null) {
            s.addValue("removalListener");
        }
        return s.toString();
    }
}
