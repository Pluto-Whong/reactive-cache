package io.github.reactive.cache.lock;

import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MonoReentrantLock extends Mono<MonoReentrantLock.LockHolder> {
    private static final Logger LOGGER = Loggers.getLogger(MonoReentrantLock.class);

    boolean fair;

    // 锁实现的关键字段，currentHolder作为锁标识，state标示重入持有的数量
    int state;
    volatile LockHolder currentHolder;
    static final AtomicReferenceFieldUpdater<MonoReentrantLock, LockHolder> CURRENT_HOLDER = AtomicReferenceFieldUpdater.newUpdater(MonoReentrantLock.class, LockHolder.class, "currentHolder");

    // 等待执行队列
    final ConcurrentLinkedDeque<CacheLockSubscriber> subscriberDeque = new ConcurrentLinkedDeque<>();

    // 唤起下一个执行任务时，为了防止出现 执行栈超出最大限度 以及 当前链路线程被其他唤起链路长时间占用 的问题，使用线程池进行链路唤醒
    private Scheduler nextScheduler = Schedulers.parallel();

    public MonoReentrantLock() {
        this(false);
    }

    public MonoReentrantLock(boolean fair) {
        this.fair = fair;
    }

    /**
     * 触发下一个执行任务时，所使用的线程池
     *
     * @param scheduler
     * @return this
     */
    public MonoReentrantLock setNextScheduler(Scheduler scheduler) {
        this.nextScheduler = scheduler;
        return this;
    }

    /**
     * 判断传入holder和当前持有锁的holder是否一致
     *
     * @param chainHolder
     * @return
     */
    private boolean sameCurrentHolder(LockHolder chainHolder) {
        return chainHolder != null && chainHolder == currentHolder;
    }

    /**
     * 抢占锁
     *
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

    /**
     * 向后执行
     *
     * @param actual
     * @param holder
     */
    private void process(CoreSubscriber<? super LockHolder> actual, LockHolder holder) {
        actual.onNext(holder);
    }

    @Override
    public void subscribe(CoreSubscriber<? super LockHolder> actual) {
        LockHolder willHolder = new LockHolder();
        this.subscribe(actual, willHolder);
    }

    private void subscribe(CoreSubscriber<? super LockHolder> actual, LockHolder lockHolder) {
        if ((fair && hasQueue()) || tryAcquire(lockHolder) < 0) {
            this.add(lockHolder, actual);
            this.tryNext();
        } else {
            this.process(actual, lockHolder);
        }
    }

    private boolean hasQueue() {
        return !subscriberDeque.isEmpty();
    }

    private void add(LockHolder toAdd, CoreSubscriber<? super LockHolder> actual) {
        subscriberDeque.offer(CacheLockSubscriber.newInstance(toAdd, actual));
    }

    private void tryNext() {
        // 尝试唤起时，可能发生 抢占失败，但执行完的线程在进行poll时
        // 可能是null（被另一个线程poll出来抢占失败，又要准备塞回去）
        // 此时是无法让链路重新唤起的，所以需要在判定有锁，或无下个任务时才能退出抢占
        // 也就用了这个无限循环直到有其他锁或无任务又或抢占成功并唤起下一个任务
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

            nextScheduler.schedule(() -> this.process(next.actual, next.holder));

            return;
        }
    }

    private void unlock(LockHolder lockHolder) {
        LockHolder currentHolder = this.currentHolder;
        if (lockHolder != currentHolder) {
            LOGGER.warn("{} unlock but has not held, current is: {}", lockHolder, currentHolder);
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
        // 根据是否尝试重入决定执行方法
        if (Objects.isNull(chainHolder)) {
            return this.flatMap(syncBlock);
        }

        return newHolderMonoSourceInstance(chainHolder).flatMap(syncBlock);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock) {
        return this.tryLock(syncBlock, null);
    }

    public boolean tryLock(Consumer<? super LockHolder> syncBlock, LockHolder chainHolder) {
        if (chainHolder == null) {
            chainHolder = new LockHolder();
        }

        // 尝试锁的情况下，若没有抢到锁则应直接返回，若抢到了，则应直接执行任务
        // 具体解锁逻辑还是要放在 syncBlock 逻辑中，根据自己的需要进行解锁
        if (tryAcquire(chainHolder) < 0) {
            return false;
        }

        syncBlock.accept(chainHolder);
        return true;
    }

    /**
     * 判定是否是当前链路持有锁
     * <p>
     * 和 {@link ReentrantLock#isHeldByCurrentThread()} 是相似的逻辑
     * 只不过是将线程换成了反应式链的概念，然后对比的依据则是 {@link LockHolder}
     *
     * @param chainHolder
     * @return
     */
    public boolean isHeldByCurrentChain(LockHolder chainHolder) {
        return this.sameCurrentHolder(chainHolder);
    }

    /**
     * 锁标示
     */
    public final class LockHolder {

        private LockHolder() {
        }

        public void unlock() {
            MonoReentrantLock.this.unlock(this);
        }

    }

    private Mono<LockHolder> newHolderMonoSourceInstance(LockHolder holder) {
        return onAssembly(new HolderMonoSource(holder));
    }

    /**
     * 解决重入加锁时的 {@link LockHolder} 传递问题
     */
    private final class HolderMonoSource extends Mono<LockHolder> {

        private final LockHolder holder;

        private HolderMonoSource(LockHolder holder) {
            this.holder = Objects.requireNonNull(holder);
        }

        @Override
        public void subscribe(CoreSubscriber<? super LockHolder> actual) {
            MonoReentrantLock.this.subscribe(actual, holder);
        }

    }

    /**
     * 排队执行订阅者
     * <p>
     * 将 {@link ReentrantLock} 中阻塞等待的线程，转变为反应式链路订阅，达到非阻塞目的
     */
    private static final class CacheLockSubscriber {

        private static CacheLockSubscriber newInstance(LockHolder holder, CoreSubscriber<? super LockHolder> actual) {
            return new CacheLockSubscriber(holder, actual);
        }

        private final LockHolder holder;
        private final CoreSubscriber<? super LockHolder> actual;

        private CacheLockSubscriber(LockHolder holder, CoreSubscriber<? super LockHolder> actual) {
            this.holder = Objects.requireNonNull(holder);
            this.actual = Objects.requireNonNull(actual);
        }

    }

}
