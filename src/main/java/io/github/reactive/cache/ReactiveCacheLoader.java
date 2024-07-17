package io.github.reactive.cache;

import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <p>
 * 缓存加载方法
 * </p>
 *
 * @author wangmin07@hotmail.com
 * @since 2024/7/3 13:24
 */
public abstract class ReactiveCacheLoader<K, V> {

    public abstract Mono<V> load(K key, V previousValue);

    public static <K, V> ReactiveCacheLoader<K, V> from(Function<K, Mono<V>> function) {
        return new FunctionToCacheLoader<>(function);
    }

    private static final class FunctionToCacheLoader<K, V> extends ReactiveCacheLoader<K, V> {

        private final Function<K, Mono<V>> computingFunction;

        public FunctionToCacheLoader(Function<K, Mono<V>> computingFunction) {
            this.computingFunction = Objects.requireNonNull(computingFunction);
        }

        @Override
        public Mono<V> load(K key, V previousValue) {
            return computingFunction.apply(Objects.requireNonNull(key));
        }

    }

    public static <V> ReactiveCacheLoader<Object, V> from(Supplier<Mono<V>> supplier) {
        return new SupplierToCacheLoader<>(supplier);
    }

    private static final class SupplierToCacheLoader<V> extends ReactiveCacheLoader<Object, V> {
        private final Supplier<Mono<V>> computingSupplier;

        public SupplierToCacheLoader(Supplier<Mono<V>> computingSupplier) {
            this.computingSupplier = Objects.requireNonNull(computingSupplier);
        }

        @Override
        public Mono<V> load(Object key, V previousValue) {
            Objects.requireNonNull(key);
            return computingSupplier.get();
        }

    }

}
