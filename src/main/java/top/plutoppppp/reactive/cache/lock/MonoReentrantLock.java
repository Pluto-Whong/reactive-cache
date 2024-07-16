package top.plutoppppp.reactive.cache.lock;

import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

public class MonoReentrantLock extends Mono<MonoReentrantLock.LockHolder> {
    private static final Logger LOGGER = Loggers.getLogger(MonoReentrantLock.class);

    volatile LockHolder currentHolder;
    int state;
    static final AtomicReferenceFieldUpdater<MonoReentrantLock, LockHolder> CURRENT_HOLDER =
            AtomicReferenceFieldUpdater.newUpdater(MonoReentrantLock.class, LockHolder.class, "currentHolder");

    final ConcurrentLinkedDeque<CacheLockSubscriber> subscriberDeque = new ConcurrentLinkedDeque<>();

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

    private void process(CoreSubscriber<? super LockHolder> actual, LockHolder holder) {
        actual.onNext(holder);
    }

    @Override
    public void subscribe(CoreSubscriber<? super LockHolder> actual) {
        LockHolder willHolder = new LockHolder();
        this.subscribe(actual, willHolder);
    }

    private void subscribe(CoreSubscriber<? super LockHolder> actual, LockHolder lockHolder) {
        if (tryAcquire(lockHolder) < 0) {
            this.add(lockHolder, actual);
            this.tryNext();
        } else {
            this.process(actual, lockHolder);
        }
    }

    private void tryNext() {
        for (; ; ) {
            if (this.currentHolder != null) {
                return;
            }

            CacheLockSubscriber next = subscriberDeque.poll();
            if (next == null) {
                return;
            }

            if (tryAcquire(next.holder) < 0) {
                // 没有抢到线程就再给塞回去
                subscriberDeque.push(next);
                continue;
            }

            Schedulers.parallel().schedule(() -> {
                this.process(next.actual, next.holder);
            });

            return;
        }
    }

    private void add(LockHolder toAdd, CoreSubscriber<? super LockHolder> actual) {
        CacheLockSubscriber s = new CacheLockSubscriber(toAdd, actual);
        subscriberDeque.offer(s);
    }

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
        });
    }

    public <R> Mono<R> lock(Function<? super LockHolder, ? extends Mono<? extends R>> syncBlock) {
        return this.lock(syncBlock, null);
    }

    public <R> Mono<R> lock(Function<? super LockHolder, ? extends Mono<? extends R>> syncBlock, LockHolder chainHolder) {
        if (Objects.isNull(chainHolder)) {
            return this.flatMap(syncBlock);
        }

        return HolderMonoSource.newInstance(chainHolder).flatMap(syncBlock);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock) {
        return this.tryLock(syncBlock, null);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock, LockHolder chainHolder) {
        if (chainHolder == null) {
            chainHolder = new LockHolder();
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

    public final class LockHolder {

        private LockHolder() {
        }

        public void unlock() {
            lock().unlock(this);
        }

        private MonoReentrantLock lock() {
            return MonoReentrantLock.this;
        }
    }

    private static final class HolderMonoSource extends Mono<LockHolder> {

        private static Mono<LockHolder> newInstance(LockHolder holder) {
            return onAssembly(new HolderMonoSource(holder));
        }

        private final LockHolder holder;

        private HolderMonoSource(LockHolder holder) {
            this.holder = Objects.requireNonNull(holder);
        }

        @Override
        public void subscribe(CoreSubscriber<? super LockHolder> actual) {
            holder.lock().subscribe(actual, holder);
        }

    }

    private static final class CacheLockSubscriber {

        private final LockHolder holder;
        private final CoreSubscriber<? super LockHolder> actual;

        CacheLockSubscriber(LockHolder holder, CoreSubscriber<? super LockHolder> actual) {
            this.holder = Objects.requireNonNull(holder);
            this.actual = Objects.requireNonNull(actual);
        }

    }

}
