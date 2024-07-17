package io.github.reactive.cache.listener;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Objects;

/**
 * A notification of the removal of a single entry. The key and/or value may be null if they were
 * already garbage collected.
 *
 * <p>Like other {@code Map.Entry} instances associated with {@code CacheBuilder}, this class holds
 * strong references to the key and value, regardless of the type of references the cache may be
 * using.
 *
 * @author Charles Fry
 * @since 10.0
 */
public final class RemovalNotification<K, V> extends SimpleImmutableEntry<K, V> {
    private final RemovalCause cause;

    /**
     * Creates a new {@code RemovalNotification} for the given {@code key}/{@code value} pair, with
     * the given {@code cause} for the removal. The {@code key} and/or {@code value} may be
     * {@code null} if they were already garbage collected.
     *
     * @since 19.0
     */
    public static <K, V> RemovalNotification<K, V> create(
            K key, V value, RemovalCause cause) {
        return new RemovalNotification<>(key, value, cause);
    }

    private RemovalNotification(K key, V value, RemovalCause cause) {
        super(key, value);
        this.cause = Objects.requireNonNull(cause);
    }

    /**
     * Returns the cause for which the entry was removed.
     */
    public RemovalCause getCause() {
        return cause;
    }

    /**
     * Returns {@code true} if there was an automatic removal due to eviction (the cause is neither
     * {@link RemovalCause#EXPLICIT} nor {@link RemovalCause#REPLACED}).
     */
    public boolean wasEvicted() {
        return cause.wasEvicted();
    }

    private static final long serialVersionUID = 0;
}
