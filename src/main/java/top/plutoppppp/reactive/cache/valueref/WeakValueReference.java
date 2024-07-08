package top.plutoppppp.reactive.cache.valueref;

import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import top.plutoppppp.reactive.cache.entry.ReferenceEntry;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * References a weak value.
 */
public class WeakValueReference<K, V> extends WeakReference<V> implements ValueReference<K, V> {
    final ReferenceEntry<K, V> entry;

    public WeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
        super(referent, queue);
        this.entry = entry;
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
        return entry;
    }

    @Override
    public void notifyNewValue(V newValue) {
    }

    @Override
    public ValueReference<K, V> copyFor(
            ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
        return new WeakValueReference<>(queue, value, entry);
    }

    @Override
    public boolean isLoading() {
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

}
