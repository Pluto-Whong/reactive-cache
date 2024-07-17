package io.github.reactive.cache.stats;

import io.github.reactive.cache.ReactiveCache;
import io.github.reactive.cache.common.Assert;
import io.github.reactive.cache.common.MoreObjects;

import java.util.Objects;

public final class CacheStats {
    private final long hitCount;
    private final long missCount;
    private final long loadSuccessCount;
    private final long loadExceptionCount;
    private final long totalLoadTime;
    private final long evictionCount;

    /**
     * Constructs a new {@code CacheStats} instance.
     *
     * <p>Five parameters of the same type in a row is a bad thing, but this class is not constructed
     * by end users and is too fine-grained for a builder.
     */
    public CacheStats(
            long hitCount,
            long missCount,
            long loadSuccessCount,
            long loadExceptionCount,
            long totalLoadTime,
            long evictionCount) {
        Assert.checkArgument(hitCount >= 0);
        Assert.checkArgument(missCount >= 0);
        Assert.checkArgument(loadSuccessCount >= 0);
        Assert.checkArgument(loadExceptionCount >= 0);
        Assert.checkArgument(totalLoadTime >= 0);
        Assert.checkArgument(evictionCount >= 0);

        this.hitCount = hitCount;
        this.missCount = missCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadExceptionCount = loadExceptionCount;
        this.totalLoadTime = totalLoadTime;
        this.evictionCount = evictionCount;
    }

    /**
     * Returns the number of times {@link ReactiveCache} lookup methods have returned either a cached or
     * uncached value. This is defined as {@code hitCount + missCount}.
     *
     * @return long
     */
    public long requestCount() {
        return hitCount + missCount;
    }

    /**
     * Returns the number of times {@link ReactiveCache} lookup methods have returned a cached value.
     *
     * @return long
     */
    public long hitCount() {
        return hitCount;
    }

    /**
     * Returns the ratio of cache requests which were hits. This is defined as
     * {@code hitCount / requestCount}, or {@code 1.0} when {@code requestCount == 0}. Note that
     * {@code hitRate + missRate =~ 1.0}.
     *
     * @return double
     */
    public double hitRate() {
        long requestCount = requestCount();
        return (requestCount == 0) ? 1.0 : (double) hitCount / requestCount;
    }

    /**
     * Returns the number of times {@link ReactiveCache} lookup methods have returned an uncached (newly
     * loaded) value, or null. Multiple concurrent calls to {@link ReactiveCache} lookup methods on an absent
     * value can result in multiple misses, all returning the results of a single cache load
     * operation.
     *
     * @return long
     */
    public long missCount() {
        return missCount;
    }

    /**
     * Returns the ratio of cache requests which were misses. This is defined as
     * {@code missCount / requestCount}, or {@code 0.0} when {@code requestCount == 0}. Note that
     * {@code hitRate + missRate =~ 1.0}. Cache misses include all requests which weren't cache hits,
     * including requests which resulted in either successful or failed loading attempts, and requests
     * which waited for other threads to finish loading. It is thus the case that
     * {@code missCount &gt;= loadSuccessCount + loadExceptionCount}. Multiple concurrent misses for
     * the same key will result in a single load operation.
     *
     * @return long
     */
    public double missRate() {
        long requestCount = requestCount();
        return (requestCount == 0) ? 0.0 : (double) missCount / requestCount;
    }

    /**
     * Returns the total number of times that {@link ReactiveCache} lookup methods attempted to load new
     * values. This includes both successful load operations, as well as those that threw exceptions.
     * This is defined as {@code loadSuccessCount + loadExceptionCount}.
     *
     * @return long
     */
    public long loadCount() {
        return loadSuccessCount + loadExceptionCount;
    }

    /**
     * Returns the number of times {@link ReactiveCache} lookup methods have successfully loaded a new value.
     * This is usually incremented in conjunction with {@link #missCount}, though {@code missCount} is
     * also incremented when an exception is encountered during cache loading (see
     * {@link #loadExceptionCount}). Multiple concurrent misses for the same key will result in a
     * single load operation. This may be incremented not in conjunction with {@code missCount} if the
     * load occurs as a result of a refresh or if the cache loader returned more items than was
     * requested. {@code missCount} may also be incremented not in conjunction with this (nor
     * {@link #loadExceptionCount}) on calls to {@code getIfPresent}.
     *
     * @return long
     */
    public long loadSuccessCount() {
        return loadSuccessCount;
    }

    /**
     * Returns the number of times {@link ReactiveCache} lookup methods threw an exception while loading a new
     * value. This is usually incremented in conjunction with {@code missCount}, though
     * {@code missCount} is also incremented when cache loading completes successfully (see
     * {@link #loadSuccessCount}). Multiple concurrent misses for the same key will result in a single
     * load operation. This may be incremented not in conjunction with {@code missCount} if the load
     * occurs as a result of a refresh or if the cache loader returned more items than was requested.
     * {@code missCount} may also be incremented not in conjunction with this (nor
     * {@link #loadSuccessCount}) on calls to {@code getIfPresent}.
     *
     * @return long
     */
    public long loadExceptionCount() {
        return loadExceptionCount;
    }

    /**
     * Returns the ratio of cache loading attempts which threw exceptions. This is defined as
     * {@code loadExceptionCount / (loadSuccessCount + loadExceptionCount)}, or {@code 0.0} when
     * {@code loadSuccessCount + loadExceptionCount == 0}.
     *
     * @return double
     */
    public double loadExceptionRate() {
        long totalLoadCount = loadSuccessCount + loadExceptionCount;
        return (totalLoadCount == 0) ? 0.0 : (double) loadExceptionCount / totalLoadCount;
    }

    /**
     * Returns the total number of nanoseconds the cache has spent loading new values. This can be
     * used to calculate the miss penalty. This value is increased every time {@code loadSuccessCount}
     * or {@code loadExceptionCount} is incremented.
     *
     * @return long
     */
    public long totalLoadTime() {
        return totalLoadTime;
    }

    /**
     * Returns the average time spent loading new values. This is defined as
     * {@code totalLoadTime / (loadSuccessCount + loadExceptionCount)}.
     *
     * @return double
     */
    public double averageLoadPenalty() {
        long totalLoadCount = loadSuccessCount + loadExceptionCount;
        return (totalLoadCount == 0) ? 0.0 : (double) totalLoadTime / totalLoadCount;
    }

    /**
     * Returns the number of times an entry has been evicted. This count does not include manual
     * {@linkplain ReactiveCache#invalidate invalidations}.
     *
     * @return long
     */
    public long evictionCount() {
        return evictionCount;
    }

    /**
     * Returns a new {@code CacheStats} representing the difference between this {@code CacheStats}
     * and {@code other}. Negative values, which aren't supported by {@code CacheStats} will be
     * rounded up to zero.
     *
     * @return CacheStatus
     */
    public CacheStats minus(CacheStats other) {
        return new CacheStats(
                Math.max(0, hitCount - other.hitCount),
                Math.max(0, missCount - other.missCount),
                Math.max(0, loadSuccessCount - other.loadSuccessCount),
                Math.max(0, loadExceptionCount - other.loadExceptionCount),
                Math.max(0, totalLoadTime - other.totalLoadTime),
                Math.max(0, evictionCount - other.evictionCount));
    }

    /**
     * Returns a new {@code CacheStats} representing the sum of this {@code CacheStats} and
     * {@code other}.
     *
     * @return CacheStatus
     */
    public CacheStats plus(CacheStats other) {
        return new CacheStats(
                hitCount + other.hitCount,
                missCount + other.missCount,
                loadSuccessCount + other.loadSuccessCount,
                loadExceptionCount + other.loadExceptionCount,
                totalLoadTime + other.totalLoadTime,
                evictionCount + other.evictionCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hitCount, missCount, loadSuccessCount, loadExceptionCount, totalLoadTime, evictionCount);
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof CacheStats) {
            CacheStats other = (CacheStats) object;
            return hitCount == other.hitCount
                    && missCount == other.missCount
                    && loadSuccessCount == other.loadSuccessCount
                    && loadExceptionCount == other.loadExceptionCount
                    && totalLoadTime == other.totalLoadTime
                    && evictionCount == other.evictionCount;
        }
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("hitCount", hitCount)
                .add("missCount", missCount)
                .add("loadSuccessCount", loadSuccessCount)
                .add("loadExceptionCount", loadExceptionCount)
                .add("totalLoadTime", totalLoadTime)
                .add("evictionCount", evictionCount)
                .toString();
    }
}
