import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import io.github.reactive.cache.common.Stopwatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 尝试直接使用Mono和ConcurrentHashMap实现一个mono缓存
 */
public class MonoCacheTestMain {

    public static class CustomThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        CustomThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() :
                    Thread.currentThread().getThreadGroup();
            namePrefix = "custom-" +
                    poolNumber.getAndIncrement() +
                    "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                    namePrefix + threadNumber.getAndIncrement(),
                    0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    public static void main(String[] args) throws Exception {
        new MonoCacheTestMain().main0();
    }

    ConcurrentMap<String, Mono<String>> cache = new ConcurrentHashMap<>();

    public void main0() throws Exception {

        ExecutorService executorService = Executors.newFixedThreadPool(32, new CustomThreadFactory());

        List<Callable<Disposable>> taskList = new ArrayList<>(200);

        Random random = new Random();
        for (int i = 0; i < 100; i++) {

//            String randomValue = String.valueOf(random.nextInt());

            String randomValue = String.valueOf(i % 7);

            String key = randomValue;

            int finalI = i;

            Callable<Disposable> runnable = () -> {
                Disposable subscribe = loadValue(key, finalI)
                        .subscribe(v -> {
                            if (!Objects.equals(randomValue, v)) {
                                throw new IllegalStateException(randomValue);
                            }
                            System.out.println(Thread.currentThread()
                                    .getName() + " : " + finalI + " : " + key + " compare");
                        });
                System.out.println(Thread.currentThread().getName() + " : " + finalI + " : " + key + " after");

                return subscribe;
            };

            taskList.add(runnable);
        }

//        executorService.invokeAll(taskList);
//        cache.invalidateAll();
//        TimeUnit.MILLISECONDS.sleep(200);

        Stopwatch stopwatch = Stopwatch.createStarted();
        executorService.invokeAll(taskList);
        System.out.println(stopwatch);

        TimeUnit.SECONDS.sleep(2);

//        executorService.invokeAll(taskList);

        TimeUnit.SECONDS.sleep(100);

        executorService.shutdown();
    }

    public Mono<String> loadValue(String key, int i) {
        System.out.println(Thread.currentThread().getName() + " : " + i + " : " + key + " before");
        return cache.computeIfAbsent(key, k -> Mono.defer(() -> {
                            try {
                                TimeUnit.MILLISECONDS.sleep(200);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
//                            System.out.println(Thread.currentThread().getName() + " : " + key);
                            return Mono.just(key);
                        })
                        .cache(Duration.ofSeconds(1))
        );
    }

}
