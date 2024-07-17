package io.github.reactive.cache.common;

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
