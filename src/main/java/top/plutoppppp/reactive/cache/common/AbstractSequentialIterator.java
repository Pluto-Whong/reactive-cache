package top.plutoppppp.reactive.cache.common;

import java.util.Iterator;
import java.util.NoSuchElementException;

public abstract class AbstractSequentialIterator<T> implements Iterator<T> {
    private T nextOrNull;

    protected AbstractSequentialIterator(T firstOrNull) {
        this.nextOrNull = firstOrNull;
    }

    protected abstract T computeNext(T previous);

    @Override
    public final boolean hasNext() {
        return nextOrNull != null;
    }

    @Override
    public final T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            return nextOrNull;
        } finally {
            nextOrNull = computeNext(nextOrNull);
        }
    }
}
