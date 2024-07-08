package top.plutoppppp.reactive.cache.stats;

import java.util.concurrent.atomic.LongAdder;

public final class SimpleStatsCounter implements StatsCounter {

    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder loadSuccessCount = new LongAdder();
    private final LongAdder loadExceptionCount = new LongAdder();
    private final LongAdder totalLoadTime = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();

    public SimpleStatsCounter() {
    }

    @Override
    public void recordHits(int count) {
        hitCount.add(count);
    }

    @Override
    public void recordMisses(int count) {
        missCount.add(count);
    }

    @Override
    public void recordLoadSuccess(long loadTime) {
        loadSuccessCount.increment();
        totalLoadTime.add(loadTime);
    }

    @Override
    public void recordLoadException(long loadTime) {
        loadExceptionCount.increment();
        totalLoadTime.add(loadTime);
    }

    @Override
    public void recordEviction() {
        evictionCount.increment();
    }

    @Override
    public CacheStats snapshot() {
        return new CacheStats(
                hitCount.sum(),
                missCount.sum(),
                loadSuccessCount.sum(),
                loadExceptionCount.sum(),
                totalLoadTime.sum(),
                evictionCount.sum());
    }

    public void incrementBy(StatsCounter other) {
        CacheStats otherStats = other.snapshot();
        hitCount.add(otherStats.hitCount());
        missCount.add(otherStats.missCount());
        loadSuccessCount.add(otherStats.loadSuccessCount());
        loadExceptionCount.add(otherStats.loadExceptionCount());
        totalLoadTime.add(otherStats.totalLoadTime());
        evictionCount.add(otherStats.evictionCount());
    }
}
