package io.github.reactive.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * 附加参数key
 * <p>
 * 因为我们是reactive场景，往往我们除了key还有其他参数需要传递
 * <p>
 * 这里封装一个方法，对value进行代理
 * </p>
 *
 * <pre>
 * AdditionalCacheKey&lt;String&gt; key = AdditionalCacheKey.of("demo",
 *         "av1", "a",
 *         "av2", "b"
 * );
 *
 * System.out.println((Object) key.getAddition("av1"));
 * System.out.println((Object) key.getAddition("av2"));
 * // 输出：
 * // a
 * // b
 *
 * key = AdditionalCacheKey.of4Class("demo",
 *         "Disposable",
 *         1
 * );
 *
 * System.out.println((Object) key.getAddition(String.class));
 * System.out.println((Object) key.getAddition(Integer.class));
 * // 输出：
 * // Disposable
 * // 1
 * </pre>
 *
 * @author wangmin07@hotmail.com
 * @since 2024/7/3 13:55
 */
public class AdditionalCacheKey<T> {

    /**
     * @param value            实际值
     * @param additionalValues 附加值，需要开发者根据自身的需求按对传入，按下标从0开始，偶数位置key，奇数位置为value，
     *                         若 additionalValues#length 为奇数，则最后的值会忽略
     * @param <T>              原始值类型
     * @return new instance
     */
    public static <T> AdditionalCacheKey<T> of(T value, Object... additionalValues) {
        AdditionalCacheKey<T> result = new AdditionalCacheKey<>(value);

        // 从1开始，可以轻松解决为奇数时出现长度超出的问题，并且解决了最后一个key若没有对应value时的问题
        for (int i = 1; i < additionalValues.length; i += 2) {
            Object k = additionalValues[i - 1];
            Object v = additionalValues[i];

            result.setAdditional(k, v);
        }

        return result;
    }

    /**
     * @param value            实际值
     * @param additionalValues 附加值，将会用 {@link Object#getClass()} 表示作为参数key
     * @param <T>              原始值类型
     * @return new instance
     */
    public static <T> AdditionalCacheKey<T> of4Class(T value, Object... additionalValues) {
        AdditionalCacheKey<T> result = new AdditionalCacheKey<>(value);

        Map<Object, Object> map = result.additionalValues;

        // 这个是根据class进行映射的，所以也会要求其中的class都不得相同
        for (Object v : additionalValues) {
            Class<?> k = v.getClass();

            Object oldValue = map.put(k, v);
            if (Objects.nonNull(oldValue)) {
                throw new IllegalArgumentException("Duplicate types: " + k.getCanonicalName());
            }
        }

        return result;
    }

    public AdditionalCacheKey(T value) {
        this.value = value;
    }

    private final T value;

    private final Map<Object, Object> additionalValues = new LinkedHashMap<>();

    public T getValue() {
        return value;
    }

    public AdditionalCacheKey<T> setAdditional(Object key, Object value) {
        this.additionalValues.put(Objects.requireNonNull(key), value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <V> V getAddition(Object key) {
        return (V) this.additionalValues.get(key);
    }

    @SuppressWarnings("unchecked")
    public <V> V getAddition(Object key, V defaultValue) {
        return (V) this.additionalValues.getOrDefault(key, defaultValue);
    }

    public <V> V getAdditionByClass(Class<V> key) {
        return key.cast(this.additionalValues.get(key));
    }

    public <V> V getAdditionByClass(Class<V> key, V defaultValue) {
        return key.cast(this.additionalValues.getOrDefault(key, defaultValue));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AdditionalCacheKey)) {
            return false;
        }
        return Objects.equals(this.value, ((AdditionalCacheKey<?>) obj).value);
    }

    @Override
    public String toString() {
        return Objects.toString(this.value);
    }
}
