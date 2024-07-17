package io.github.reactive.cache.valueref;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import io.github.reactive.cache.ReactiveCacheLoader;
import io.github.reactive.cache.ReactiveLocalCache;
import io.github.reactive.cache.common.Stopwatch;
import io.github.reactive.cache.entry.ReferenceEntry;

import java.lang.ref.ReferenceQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static io.github.reactive.cache.ReactiveLocalCache.unset;

public class LoadingValueReference<K, V> implements ValueReference<K, V> {

    volatile ValueReference<K, V> oldValue;

    final CompletableFuture<V> futureValue = new CompletableFuture<>();
    final Stopwatch stopwatch = Stopwatch.createUnstarted();

    public LoadingValueReference() {
        this(ReactiveLocalCache.unset());
    }

    public LoadingValueReference(ValueReference<K, V> oldValue) {
        this.oldValue = oldValue;
    }

    @Override
    public boolean isLoading() {
        return true;
    }

    @Override
    public boolean isActive() {
        return oldValue.isActive();
    }

    @Override
    public int getWeight() {
        return oldValue.getWeight();
    }

    public boolean set(V newValue) {
        return futureValue.complete(newValue);
    }

    public boolean setException(Throwable t) {
        return futureValue.completeExceptionally(t);
    }

    @Override
    public void notifyNewValue(V newValue) {
        if (newValue != null) {
            // The pending load was clobbered by a manual write.
            // Unblock all pending gets, and have them return the new value.
            set(newValue);
        } else {
            // The pending load was removed. Delay notifications until loading completes.
            oldValue = unset();
        }
    }

    public Mono<V> loadFuture(K key, ReactiveCacheLoader<? super K, V> loader, Scheduler scheduler) {
        Mono<V> defer = Mono.defer(() -> {
            stopwatch.start();
            return loader.load(key, oldValue.get());
        });
        // 解决可能线程池满载而被拒绝执行的问题
        // 尽量放置在最前，如果被拒绝，是会继续向后执行的，也就是下面的 doOnError
        if (Objects.nonNull(scheduler)) {
            defer = defer.subscribeOn(scheduler);
        }

        return defer
                .switchIfEmpty(Mono.fromRunnable(() -> this.set(null)))
                .doOnNext(this::set)
                .doOnError(this::setException);
    }

    public long elapsedNanos() {
        return stopwatch.elapsed(NANOSECONDS);
    }

    @Override
    public V get() {
        return oldValue.get();
    }

    @Override
    public void waitForValue(MonoSink<V> sink, Scheduler scheduler) {
        final BiConsumer<V, ? super Throwable> action = (v, e) -> {
            if (Objects.nonNull(e)) {
                sink.error(e);
            } else {
                sink.success(v);
            }
        };

        BiConsumer<V, ? super Throwable> realAction;
        if (Objects.nonNull(scheduler)) {
            realAction = (v, e) -> scheduler.schedule(() -> action.accept(v, e));
        } else {
            realAction = action;
        }

        futureValue.whenComplete(realAction);
    }

    public ValueReference<K, V> getOldValue() {
        return oldValue;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
        return null;
    }

    @Override
    public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
        return this;
    }
}
