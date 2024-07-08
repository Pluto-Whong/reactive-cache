package top.plutoppppp.reactive.cache;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import top.plutoppppp.reactive.cache.stats.CacheStats;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

public interface ReactiveCache<K, V> {

    /**
     * 存在时直接返回
     * 不存在直接返回 {@link Mono#empty()}
     * 不会去尝试拉取值
     *
     * @param key
     * @return
     */
    Mono<V> getIfPresent(K key);

    /**
     * 获得值，若不存在则通过 {@param loader} 拉取
     *
     * @param key
     * @param loader
     * @return
     * @throws ExecutionException
     */
    Mono<V> get(K key, Callable<? extends Mono<V>> loader) throws ExecutionException;

    /**
     * 根据key获取所有值
     *
     * @param keys
     * @return
     */
    Flux<Tuple2<K, V>> getAllPresent(Iterable<? extends K> keys);

    /**
     * 设置值
     *
     * @param key
     * @param value
     * @return
     */
    Mono<V> put(K key, Mono<V> value);

    /**
     * 批量设置值
     *
     * @param m
     */
    void putAll(Map<? extends K, ? extends Mono<V>> m);

    /**
     * 使某个值失效
     *
     * @param key
     * @return
     */
    Mono<V> invalidate(Object key);

    /**
     * 批量失效
     *
     * @param keys
     */
    void invalidateAll(Iterable<?> keys);

    /**
     * 所有值失效
     */
    void invalidateAll();

    /**
     * 大小
     *
     * @return
     */
    long size();

    /**
     * 缓存状态
     *
     * @return
     */
    CacheStats stats();

    ConcurrentMap<K, Mono<V>> asMap();

    /**
     * 清理过期值
     */
    void cleanUp();

}
