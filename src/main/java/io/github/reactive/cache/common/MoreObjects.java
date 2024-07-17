package io.github.reactive.cache.common;

import java.util.Arrays;
import java.util.Objects;

public final class MoreObjects {

    public static <T> T firstNonNull(T first, T second) {
        return first != null ? first : Objects.requireNonNull(second);
    }

    public static ToStringHelper toStringHelper(Object self) {
        return new ToStringHelper(self.getClass().getSimpleName());
    }

    public static ToStringHelper toStringHelper(Class<?> clazz) {
        return new ToStringHelper(clazz.getSimpleName());
    }

    public static ToStringHelper toStringHelper(String className) {
        return new ToStringHelper(className);
    }

    public static final class ToStringHelper {
        private final String className;
        private final ValueHolder holderHead = new ValueHolder();
        private ValueHolder holderTail = holderHead;
        private boolean omitNullValues = false;

        private ToStringHelper(String className) {
            this.className = Objects.requireNonNull(className);
        }

        public ToStringHelper omitNullValues() {
            omitNullValues = true;
            return this;
        }

        public ToStringHelper add(String name, Object value) {
            return addHolder(name, value);
        }


        public ToStringHelper add(String name, boolean value) {
            return addHolder(name, String.valueOf(value));
        }


        public ToStringHelper add(String name, char value) {
            return addHolder(name, String.valueOf(value));
        }

        public ToStringHelper add(String name, double value) {
            return addHolder(name, String.valueOf(value));
        }

        public ToStringHelper add(String name, float value) {
            return addHolder(name, String.valueOf(value));
        }


        public ToStringHelper add(String name, int value) {
            return addHolder(name, String.valueOf(value));
        }

        public ToStringHelper add(String name, long value) {
            return addHolder(name, String.valueOf(value));
        }

        public ToStringHelper addValue(Object value) {
            return addHolder(value);
        }

        public ToStringHelper addValue(boolean value) {
            return addHolder(String.valueOf(value));
        }

        public ToStringHelper addValue(char value) {
            return addHolder(String.valueOf(value));
        }

        public ToStringHelper addValue(double value) {
            return addHolder(String.valueOf(value));
        }

        public ToStringHelper addValue(float value) {
            return addHolder(String.valueOf(value));
        }

        public ToStringHelper addValue(int value) {
            return addHolder(String.valueOf(value));
        }

        public ToStringHelper addValue(long value) {
            return addHolder(String.valueOf(value));
        }

        @Override
        public String toString() {
            // create a copy to keep it consistent in case value changes
            boolean omitNullValuesSnapshot = omitNullValues;
            String nextSeparator = "";
            StringBuilder builder = new StringBuilder(32).append(className).append('{');
            for (ValueHolder valueHolder = holderHead.next;
                 valueHolder != null;
                 valueHolder = valueHolder.next) {
                Object value = valueHolder.value;
                if (!omitNullValuesSnapshot || value != null) {
                    builder.append(nextSeparator);
                    nextSeparator = ", ";

                    if (valueHolder.name != null) {
                        builder.append(valueHolder.name).append('=');
                    }
                    if (value != null && value.getClass().isArray()) {
                        Object[] objectArray = {value};
                        String arrayString = Arrays.deepToString(objectArray);
                        builder.append(arrayString, 1, arrayString.length() - 1);
                    } else {
                        builder.append(value);
                    }
                }
            }
            return builder.append('}').toString();
        }

        private ValueHolder addHolder() {
            ValueHolder valueHolder = new ValueHolder();
            holderTail = holderTail.next = valueHolder;
            return valueHolder;
        }

        private ToStringHelper addHolder(Object value) {
            ValueHolder valueHolder = addHolder();
            valueHolder.value = value;
            return this;
        }

        private ToStringHelper addHolder(String name, Object value) {
            ValueHolder valueHolder = addHolder();
            valueHolder.value = value;
            valueHolder.name = Objects.requireNonNull(name);
            return this;
        }

        private static final class ValueHolder {
            String name;
            Object value;
            ValueHolder next;
        }
    }

    private MoreObjects() {
    }
}
