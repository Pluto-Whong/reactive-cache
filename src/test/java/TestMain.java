import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import top.plutoppppp.reactive.cache.ReactiveCacheBuilder;
import top.plutoppppp.reactive.cache.ReactiveCacheLoader;
import top.plutoppppp.reactive.cache.ReactiveLoadingCache;
import top.plutoppppp.reactive.cache.common.Stopwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TestMain {

    public static void main(String[] args) throws Exception {
        new TestMain().main0();
    }

    public void main0() throws Exception {
        ReactiveLoadingCache<String, String> cache = ReactiveCacheBuilder.newBuilder()
                .initialCapacity(2048)
                .maximumSize(2048)
                .concurrencyLevel(128)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(50, TimeUnit.SECONDS)
                .build(ReactiveCacheLoader.from(this::loadValue));

        ExecutorService executorService = Executors.newFixedThreadPool(32);

        List<Callable<Disposable>> taskList = new ArrayList<>(200);

        Random random = new Random();
        for (int i = 0; i < 10000; i++) {

//            String randomValue = String.valueOf(random.nextInt());

            String randomValue = String.valueOf(i % 19);

            String key = randomValue;

            int finalI = i;

            Callable<Disposable> runnable = () -> cache.get(key)
                    .subscribe(v -> {
                        if (!Objects.equals(randomValue, v)) {
                            throw new IllegalStateException(randomValue);
                        }
//                        System.err.println(Thread.currentThread().getName() + " : " + finalI + " : " + randomValue);
                    });

            taskList.add(runnable);
        }

//        executorService.invokeAll(taskList);
//        cache.invalidateAll();
//        TimeUnit.MILLISECONDS.sleep(200);

        Stopwatch stopwatch = Stopwatch.createStarted();
        executorService.invokeAll(taskList);
        System.out.println(stopwatch);

        executorService.shutdown();
    }

    public Mono<String> loadValue(String key) {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
//        System.out.println(key);
        return Mono.just(key);
//        return Mono.empty();
    }

}
