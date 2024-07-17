package io.github.reactive.cache;

import io.github.reactive.cache.stats.CacheStats;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;

public interface ReactiveCache<K, V> {

    /**
     * 存在时直接返回
     * 不存在直接返回 {@link Mono#empty()}
     * 不会去尝试拉取值
     *
     * @param key 缓存key
     * @return 值
     */
    Mono<V> getIfPresent(K key);

    /**
     * 获得值，若不存在则通过 loader 拉取
     *
     * @param key    缓存key
     * @param loader 加载方法
     * @return 值
     */
    Mono<V> get(K key, Callable<? extends Mono<V>> loader);

    /**
     * 根据key获取所有值
     *
     * @param keys 缓存key
     * @return 目标值集合
     */
    Flux<Tuple2<K, V>> getAllPresent(Iterable<? extends K> keys);

    /**
     * 设置值
     *
     * @param key   缓存key
     * @param value 替换值
     * @return 旧值
     */
    Mono<V> put(K key, Mono<V> value);

    /**
     * 批量设置值
     *
     * @param m 来源map
     */
    void putAll(Map<? extends K, ? extends Mono<V>> m);

    /**
     * 使某个值失效
     *
     * @param key 目标key
     * @return 旧值
     */
    Mono<V> invalidate(Object key);

    /**
     * 批量失效
     *
     * @param keys 目标key
     */
    void invalidateAll(Iterable<?> keys);

    /**
     * 所有值失效
     */
    void invalidateAll();

    /**
     * 大小
     *
     * @return size
     */
    long size();

    /**
     * 缓存状态
     *
     * @return CacheStats
     */
    CacheStats stats();

    ConcurrentMap<K, Mono<V>> asMap();

    /**
     * 清理过期值
     */
    void cleanUp();

}
