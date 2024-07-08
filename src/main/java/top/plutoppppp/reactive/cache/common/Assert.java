package top.plutoppppp.reactive.cache.common;

import java.util.Objects;

public class Assert {

    public static void checkArgument(boolean flag) {
        if (!flag) {
            throw new IllegalArgumentException();
        }
    }

    public static void checkArgument(boolean flag, String message, Object... params) {
        if (!flag) {
            throw new IllegalArgumentException(String.format(message, params));
        }
    }

    public static void state(boolean flag, String message) {
        if (!flag) {
            throw new IllegalStateException(message);
        }
    }

    public static void notNull(Object o, String message) {
        if (Objects.isNull(o)) {
            throw new IllegalArgumentException(message);
        }
    }

    public static <T> T checkNotNull(T o) {
        if (Objects.isNull(o)) {
            throw new IllegalArgumentException();
        }
        return o;
    }

    public static <T> T checkNotNull(T o, String message) {
        if (Objects.isNull(o)) {
            throw new IllegalArgumentException(message);
        }
        return o;
    }

    public static void checkState(boolean flag) {
        if (!flag) {
            throw new IllegalStateException();
        }
    }

    public static void checkState(boolean flag, String message, Object... params) {
        if (!flag) {
            throw new IllegalStateException(String.format(message, params));
        }
    }

}
