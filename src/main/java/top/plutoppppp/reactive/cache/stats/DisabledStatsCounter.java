package top.plutoppppp.reactive.cache.stats;

public enum DisabledStatsCounter implements StatsCounter {
    INSTANCE;

    private final CacheStats EMPTY_STATS = new CacheStats(0, 0, 0, 0, 0, 0);

    @Override
    public void recordHits(int count) {
    }

    @Override
    public void recordMisses(int count) {
    }

    @Override
    public void recordLoadSuccess(long loadTime) {
    }

    @Override
    public void recordLoadException(long loadTime) {
    }

    @Override
    public void recordEviction() {
    }

    @Override
    public CacheStats snapshot() {
        return EMPTY_STATS;
    }
}
