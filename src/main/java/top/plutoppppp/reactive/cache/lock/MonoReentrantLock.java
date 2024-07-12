package top.plutoppppp.reactive.cache.lock;

import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;

public class MonoReentrantLock extends Mono<MonoReentrantLock.LockHolder> {
    private static final Logger LOGGER = Loggers.getLogger(MonoReentrantLock.class);

    volatile LockHolder currentHolder;
    int state;
    static final AtomicReferenceFieldUpdater<MonoReentrantLock, LockHolder> CURRENT_HOLDER =
            AtomicReferenceFieldUpdater.newUpdater(MonoReentrantLock.class, LockHolder.class, "currentHolder");

    final ConcurrentLinkedDeque<LockHolder> subscriberDeque = new ConcurrentLinkedDeque<>();

    private boolean sameCurrentHolder(LockHolder chainHolder) {
        return chainHolder != null && chainHolder == currentHolder;
    }

    /**
     * @param willHolder
     * @return 负数-失败 1-该反应链初次抢占 >1-重进占用
     */
    private int tryAcquire(LockHolder willHolder) {
        if (willHolder == null) {
            return -1;
        }

        if (sameCurrentHolder(willHolder)) {
            state++;
            return state;
        }

        if (CURRENT_HOLDER.compareAndSet(this, null, willHolder)) {
            state = 1;
            return state;
        }

        return -2;
    }

    private void process(LockHolder holder) {
        holder.actual.onNext(holder);
    }

    ConcurrentHashMap<Object, Boolean> alreadyMap = new ConcurrentHashMap<>();

    @Override
    public void subscribe(CoreSubscriber<? super LockHolder> actual) {
        LockHolder willHolder = new LockHolder(this, actual);

        if (tryAcquire(willHolder) < 0) {
            this.add(willHolder);
            this.tryNext();
        } else {
            this.process(willHolder);
        }
    }

    private void tryNext() {
        if (this.currentHolder != null) {
            return;
        }

        LockHolder nextHolder = subscriberDeque.poll();
        if (nextHolder == null) {
            return;
        }

        if (tryAcquire(nextHolder) < 0) {
            // 没有抢到线程就再给塞回去
            subscriberDeque.push(nextHolder);
            return;
        }

        Schedulers.boundedElastic().schedule(() -> {
            this.process(nextHolder);
        });
    }

    private void add(LockHolder toAdd) {
        subscriberDeque.offer(toAdd);
    }

    LongAdder longAdder = new LongAdder();

    private void unlock(LockHolder lockHolder) {
        LockHolder currentHolder = this.currentHolder;
        if (lockHolder != currentHolder) {
            LOGGER.debug("{} unlock but has not held, current is: {}", lockHolder, currentHolder);
            return;
        }

        if (--this.state > 0) {
            return;
        }

        this.currentHolder = null;

        this.tryNext();
    }

    public Mono<Void> lock(Consumer<? super LockHolder> syncBlock) {
        return this.lock(holder -> {
            syncBlock.accept(holder);
            return Mono.empty();
        }, null);
    }

    public <R> Mono<R> lock(Function<? super LockHolder, ? extends Mono<? extends R>> syncBlock) {
        return this.lock(syncBlock, null);
    }

    public <R> Mono<R> lock(Function<? super LockHolder, ? extends Mono<? extends R>> syncBlock, LockHolder chainHolder) {
        if (tryAcquire(chainHolder) < 0) {
            return this.flatMap(syncBlock);
        }

        return Mono.just(chainHolder).flatMap(syncBlock);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock) {
        return this.tryLock(syncBlock, null);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock, LockHolder chainHolder) {
        if (chainHolder == null) {
            chainHolder = new LockHolder(this, null);
        }

        if (tryAcquire(chainHolder) < 0) {
            return false;
        }

        syncBlock.accept(chainHolder);
        return true;
    }

    public boolean isHeldByCurrentChain(LockHolder chainHolder) {
        return this.sameCurrentHolder(chainHolder);
    }

    public static final class LockHolder {

        private final MonoReentrantLock lock;

        private final CoreSubscriber<? super LockHolder> actual;

        LockHolder(MonoReentrantLock lock, CoreSubscriber<? super LockHolder> actual) {
            this.lock = lock;
            this.actual = actual;
        }

        public void unlock() {
            lock.unlock(this);
        }
    }

}
