package io.github.reactive.cache;

import reactor.core.publisher.Mono;

public interface ReactiveLoadingCache<K, V> extends ReactiveCache<K, V> {

    Mono<V> get(K key);

    void refresh(K key);

}
