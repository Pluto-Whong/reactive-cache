package io.github.reactive.cache;


/**
 * Calculates the weights of cache entries.
 *
 * @author Charles Fry
 * @since 11.0
 */
public interface Weigher<K, V> {

    /**
     * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
     * relative to each other.
     *
     * @return the weight of the entry; must be non-negative
     */
    int weigh(K key, V value);
}
