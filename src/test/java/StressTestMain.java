import reactor.core.publisher.Mono;
import io.github.reactive.cache.ReactiveCacheBuilder;
import io.github.reactive.cache.ReactiveCacheLoader;
import io.github.reactive.cache.ReactiveLoadingCache;
import io.github.reactive.cache.exception.InvalidCacheLoadException;

import java.time.Duration;
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
                .initialCapacity(1024)
                .maximumSize(1024)
                .concurrencyLevel(64)
                .expireAfterWrite(6, TimeUnit.SECONDS)
                .refreshAfterWrite(5, TimeUnit.SECONDS)
                .recordStats()
                .build(ReactiveCacheLoader.from(this::loadValue));

        String[] keys = map.keySet().toArray(new String[0]);

        ExecutorService executorService = Executors.newCachedThreadPool();

        LongAdder missCount = new LongAdder();
        LongAdder valueCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        LongAdder totalCount = new LongAdder();
        LongAdder getTotalCount = new LongAdder();

        AtomicInteger index = new AtomicInteger();
        AtomicLong lastTotalCount = new AtomicLong();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final int concurrentCount = 32;
        final long interval = 120L * 1000L;

        CountDownLatch countDownLatch = new CountDownLatch(concurrentCount);

        Runnable dataPrint = () -> {
            long currentTotalCount = totalCount.longValue();
            long valueC = valueCount.longValue();
            long missC = missCount.longValue();
            long errorC = errorCount.longValue();
            System.err.println(index.incrementAndGet()
                    + " last: " + lastTotalCount.longValue()
                    + " current: " + currentTotalCount
                    + " qps: " + (currentTotalCount - lastTotalCount.get())
                    + " get: " + getTotalCount.longValue()
                    + " countDownLatch: " + countDownLatch.getCount()
                    + " valueC: " + valueC
                    + " missC: " + missC
                    + " errorC: " + errorC
            );
            lastTotalCount.set(currentTotalCount);
        };
        scheduledExecutorService.scheduleAtFixedRate(dataPrint, 0, 5, TimeUnit.SECONDS);

        for (int i = 0; i < concurrentCount; i++) {
            TimeUnit.MILLISECONDS.sleep(1 + random.nextInt(5));
            Random randomInner = new Random();

            executorService.submit(() -> {
                final long start = System.currentTimeMillis();
                try {
                    while (System.currentTimeMillis() - start < interval) {
                        String key = keys[randomInner.nextInt(keys.length)];
                        String value = RANDOM_MAP.get(key);
                        getTotalCount.add(1);
                        String block = cache.get(key)
                                .onErrorResume(InvalidCacheLoadException.class, e -> {
                                    if (Objects.nonNull(value)) {
                                        new IllegalStateException("key: " + key + " value: " + value + " v: InvalidCacheLoadException").printStackTrace();
                                        return Mono.empty();
                                    }
                                    missCount.add(1);
                                    return Mono.empty();
                                })
                                .onErrorResume(e -> {
                                    errorCount.add(1);
                                    e.printStackTrace();
                                    return Mono.empty();
                                })
                                .flatMap(v -> {
                                    valueCount.add(1);
                                    return Mono.just(v);
                                })
                                .block();
                        if (Objects.nonNull(block) && !Objects.equals(value, block)) {
                            throw new IllegalStateException("key: " + key + " value: " + value + " v: " + block);
                        }
                        totalCount.add(1);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        countDownLatch.await();

        System.out.println(cache.stats());

        System.out.println(missCount);
        System.out.println(valueCount);

        dataPrint.run();

        executorService.shutdown();
        scheduledExecutorService.shutdown();
    }

    public Mono<String> loadValue(String key) {
//        try {
//            TimeUnit.MILLISECONDS.sleep(26);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        return Mono.justOrEmpty(RANDOM_MAP.get(key));

        return Mono
//                .just(1)
                .delay(Duration.ofMillis(3))
                .flatMap(v -> Mono.justOrEmpty(RANDOM_MAP.get(key)));
    }

}
