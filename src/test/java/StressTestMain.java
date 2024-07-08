import reactor.core.publisher.Mono;
import top.plutoppppp.reactive.cache.ReactiveCacheBuilder;
import top.plutoppppp.reactive.cache.ReactiveCacheLoader;
import top.plutoppppp.reactive.cache.ReactiveLoadingCache;
import top.plutoppppp.reactive.cache.exception.InvalidCacheLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 压力测试
 */
public class StressTestMain {

    public static final String RANDOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static final Random random = new Random();

    public static String randomStr(int min, int max) {
        int length = min + random.nextInt(max - min);

        StringBuilder stringBuilder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            stringBuilder.append(RANDOM_CHARS.charAt(random.nextInt(RANDOM_CHARS.length())));
        }

        return stringBuilder.toString();
    }

    public static Map<String, String> RANDOM_MAP = new HashMap<>();

    public static void main(String[] args) throws Exception {

        for (int i = 0; i < 1; i++) {
            RANDOM_MAP.put(randomStr(8, 12), null);
        }

        for (int i = 0; i < 512; i++) {
            RANDOM_MAP.put(randomStr(4, 8), randomStr(10, 16));
        }

        System.out.println(RANDOM_MAP);

        new StressTestMain().main0(RANDOM_MAP);
    }

    public void main0(Map<String, String> map) throws Exception {
        ReactiveLoadingCache<String, String> cache = ReactiveCacheBuilder.newBuilder()
                .initialCapacity(256)
                .maximumSize(256)
                .concurrencyLevel(16)
                .expireAfterWrite(6, TimeUnit.SECONDS)
                .refreshAfterWrite(5, TimeUnit.SECONDS)
                .recordStats()
                .build(ReactiveCacheLoader.from(this::loadValue));

        String[] keys = map.keySet().toArray(new String[0]);

        ExecutorService executorService = Executors.newCachedThreadPool();

        LongAdder missCount = new LongAdder();
        LongAdder valueCount = new LongAdder();
        LongAdder totalCount = new LongAdder();

        AtomicInteger index = new AtomicInteger();
        AtomicLong lastTotalCount = new AtomicLong();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            long currentTotalCount = totalCount.longValue();
            System.err.println(index.incrementAndGet() + " last: " + lastTotalCount.longValue() + " current: " + currentTotalCount + " qps: " + (currentTotalCount - lastTotalCount.get()));
            lastTotalCount.set(currentTotalCount);
        }, 0, 5, TimeUnit.SECONDS);

        final int concurrentCount = 32;
        final long interval = 10L * 60L * 1000L;

        CountDownLatch countDownLatch = new CountDownLatch(concurrentCount);
        for (int i = 0; i < concurrentCount; i++) {
            Random randomInner = new Random(random.nextInt());

            executorService.submit(() -> {
                final long start = System.currentTimeMillis();
                try {
                    while (System.currentTimeMillis() - start < interval) {
                        String key = keys[randomInner.nextInt(keys.length)];
                        String value = RANDOM_MAP.get(key);
                        cache.get(key)
                                .onErrorResume(InvalidCacheLoadException.class, e -> {
                                    if (Objects.nonNull(value)) {
                                        throw new IllegalStateException("key: " + key + " value: " + value + " v: InvalidCacheLoadException");
                                    }
                                    missCount.add(1);
                                    totalCount.add(1);
                                    return Mono.empty();
                                })
                                .subscribe(v -> {
                                    if (!Objects.equals(value, v)) {
                                        throw new IllegalStateException("key: " + key + " value: " + value + " v: " + v);
                                    }
                                    valueCount.add(1);
                                    totalCount.add(1);
//                                    System.out.println("key: " + key + " value: " + value + " v: " + v);
                                });
                    }
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        countDownLatch.await();

        System.out.println(cache.stats());

        System.out.println(missCount);
        System.out.println(valueCount);

        executorService.shutdown();
        scheduledExecutorService.shutdown();
    }

    public Mono<String> loadValue(String key) {
        try {
            TimeUnit.MILLISECONDS.sleep(26);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return Mono.justOrEmpty(RANDOM_MAP.get(key));

//        return Mono.delay(Duration.ofMillis(26), Schedulers.boundedElastic())
//                .flatMap(v -> Mono.justOrEmpty(RANDOM_MAP.get(key)));
    }

}
