package io.github.reactive.cache;

import reactor.core.publisher.Mono;

import java.util.Objects;

public class ReactiveLocalLoadingCache<K, V> extends ReactiveLocalManualCache<K, V> implements ReactiveLoadingCache<K, V> {

    ReactiveLocalLoadingCache(ReactiveCacheBuilder<? super K, ? super V> builder, ReactiveCacheLoader<? super K, V> loader) {
        super(new ReactiveLocalCache<>(builder, Objects.requireNonNull(loader)));
    }

    // LoadingCache methods

    @Override
    public Mono<V> get(K key) {
        return localCache.getOrLoad(key);
    }

    @Override
    public void refresh(K key) {
        localCache.refresh(key);
    }

}
