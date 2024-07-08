package top.plutoppppp.reactive.cache.valueref;

import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import top.plutoppppp.reactive.cache.entry.ReferenceEntry;

import java.lang.ref.ReferenceQueue;

/**
 * References a strong value.
 */
public class StrongValueReference<K, V> implements ValueReference<K, V> {
    final V referent;

    public StrongValueReference(V referent) {
        this.referent = referent;
    }

    @Override
    public V get() {
        return referent;
    }

    @Override
    public void waitForValue(MonoSink<V> sink, Scheduler scheduler) {
        sink.success(this.get());
    }

    @Override
    public int getWeight() {
        return 1;
    }

    @Override
    public ReferenceEntry<K, V> getEntry() {
        return null;
    }

    @Override
    public ValueReference<K, V> copyFor(
            ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
        return this;
    }

    @Override
    public boolean isLoading() {
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void notifyNewValue(V newValue) {
    }
}
