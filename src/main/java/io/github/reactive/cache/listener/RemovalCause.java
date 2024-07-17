package io.github.reactive.cache.listener;

/**
 * The reason why a cached entry was removed.
 *
 * @author Charles Fry
 * @since 10.0
 */
public enum RemovalCause {

    EXPLICIT {
        @Override
        public boolean wasEvicted() {
            return false;
        }
    },

    REPLACED {
        @Override
        public boolean wasEvicted() {
            return false;
        }
    },

    COLLECTED {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    },

    EXPIRED {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    },

    SIZE {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    };

    public abstract boolean wasEvicted();
}
