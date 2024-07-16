import reactor.core.publisher.Mono;
import top.plutoppppp.reactive.cache.ReactiveCacheBuilder;
import top.plutoppppp.reactive.cache.ReactiveCacheLoader;
import top.plutoppppp.reactive.cache.ReactiveLoadingCache;
import top.plutoppppp.reactive.cache.exception.InvalidCacheLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 正确性校验
 */
public class CorrectnessTestMain {

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

        new CorrectnessTestMain().main0(RANDOM_MAP);
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

        final int concurrentCount = 32;
        long interval = 10L * 1000L;

        CountDownLatch countDownLatch = new CountDownLatch(concurrentCount);
        for (int i = 0; i < concurrentCount; i++) {
            Random randomInner = new Random(random.nextInt());

            executorService.submit(() -> {
                long start = System.currentTimeMillis();
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
                                    return Mono.empty();
                                })
                                .subscribe(v -> {
                                    if (!Objects.equals(value, v)) {
                                        throw new IllegalStateException("key: " + key + " value: " + value + " v: " + v);
                                    }
                                    valueCount.add(1);
//                                    System.out.println("key: " + key + " value: " + value + " v: " + v);
                                });
                    }
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        System.err.println(start);
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.err.println(end);
        System.err.println("totals: " + (end - start));

        System.out.println(cache.stats());

        System.out.println(missCount);
        System.out.println(valueCount);

        executorService.shutdown();
    }

    public Mono<String> loadValue(String key) {
        return Mono.justOrEmpty(RANDOM_MAP.get(key));
    }

}
