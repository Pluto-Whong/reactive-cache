package top.plutoppppp.reactive.cache;

import top.plutoppppp.reactive.cache.common.Equivalence;
import top.plutoppppp.reactive.cache.entry.ReferenceEntry;
import top.plutoppppp.reactive.cache.valueref.*;

/**
 * <p>
 *
 * </p>
 *
 * @author wangmin07@hotmail.com
 * @since 2024/7/3 15:57
 */
public enum Strength {
    STRONG {
        @Override
        <K, V> ValueReference<K, V> referenceValue(
                ReactiveSegment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
            return (weight == 1)
                    ? new StrongValueReference<K, V>(value)
                    : new WeightedStrongValueReference<K, V>(value, weight);
        }

        @Override
        Equivalence<Object> defaultEquivalence() {
            return Equivalence.equals();
        }
    },
    SOFT {
        @Override
        <K, V> ValueReference<K, V> referenceValue(
                ReactiveSegment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
            return (weight == 1)
                    ? new SoftValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                    : new WeightedSoftValueReference<K, V>(
                    segment.valueReferenceQueue, value, entry, weight);
        }

        @Override
        Equivalence<Object> defaultEquivalence() {
            return Equivalence.identity();
        }
    },
    WEAK {
        @Override
        <K, V> ValueReference<K, V> referenceValue(
                ReactiveSegment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight) {
            return (weight == 1)
                    ? new WeakValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                    : new WeightedWeakValueReference<K, V>(
                    segment.valueReferenceQueue, value, entry, weight);
        }

        @Override
        Equivalence<Object> defaultEquivalence() {
            return Equivalence.identity();
        }
    };

    /**
     * Creates a reference for the given value according to this value strength.
     */
    abstract <K, V> ValueReference<K, V> referenceValue(
            ReactiveSegment<K, V> segment, ReferenceEntry<K, V> entry, V value, int weight);

    /**
     * Returns the default equivalence strategy used to compare and hash keys or values referenced
     * at this strength. This strategy will be used unless the user explicitly specifies an
     * alternate strategy.
     */
    abstract Equivalence<Object> defaultEquivalence();
}
