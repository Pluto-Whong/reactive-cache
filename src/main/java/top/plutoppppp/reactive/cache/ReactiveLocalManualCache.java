package top.plutoppppp.reactive.cache;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import top.plutoppppp.reactive.cache.stats.CacheStats;
import top.plutoppppp.reactive.cache.stats.SimpleStatsCounter;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import static top.plutoppppp.reactive.cache.common.Assert.checkNotNull;

public class ReactiveLocalManualCache<K, V> implements ReactiveCache<K, V> {

    final ReactiveLocalCache<K, V> localCache;

    ReactiveLocalManualCache(ReactiveCacheBuilder<? super K, ? super V> builder) {
        this(new ReactiveLocalCache<>(builder, null));
    }

    ReactiveLocalManualCache(ReactiveLocalCache<K, V> localCache) {
        this.localCache = localCache;
    }

    // Cache methods

    @Override
    public Mono<V> getIfPresent(K key) {
        return localCache.getIfPresent(key);
    }

    @Override
    public Mono<V> get(K key, final Callable<? extends Mono<V>> valueLoader) throws ExecutionException {
        checkNotNull(valueLoader);
        return localCache.get(key, new ReactiveCacheLoader<K, V>() {
            @Override
            public Mono<V> load(K key, V previousValue) {
                try {
                    return valueLoader.call();
                } catch (Throwable e) {
                    return Mono.error(e);
                }
            }
        });
    }

    @Override
    public Flux<Tuple2<K, V>> getAllPresent(Iterable<? extends K> keys) {
        return localCache.getAllPresent(keys);
    }

    @Override
    public Mono<V> put(K key, Mono<V> value) {
        return localCache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends Mono<V>> m) {
        localCache.putAll(m);
    }

    @Override
    public Mono<V> invalidate(Object key) {
        checkNotNull(key);
        return localCache.remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
        localCache.invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
        localCache.clear();
    }

    @Override
    public long size() {
        return localCache.longSize();
    }

    @Override
    public ConcurrentMap<K, Mono<V>> asMap() {
        return localCache;
    }

    @Override
    public CacheStats stats() {
        SimpleStatsCounter aggregator = new SimpleStatsCounter();
        aggregator.incrementBy(localCache.globalStatsCounter);
        for (ReactiveSegment<K, V> segment : localCache.segments) {
            aggregator.incrementBy(segment.statsCounter);
        }
        return aggregator.snapshot();
    }

    @Override
    public void cleanUp() {
        localCache.cleanUp();
    }

}
