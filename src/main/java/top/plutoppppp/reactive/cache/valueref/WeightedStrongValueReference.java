package top.plutoppppp.reactive.cache.valueref;

/**
 * References a strong value.
 */
public final class WeightedStrongValueReference<K, V> extends StrongValueReference<K, V> {
    final int weight;

    public WeightedStrongValueReference(V referent, int weight) {
        super(referent);
        this.weight = weight;
    }

    @Override
    public int getWeight() {
        return weight;
    }
}
