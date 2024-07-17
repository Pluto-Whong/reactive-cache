import reactor.core.publisher.Mono;
import io.github.reactive.cache.ReactiveCacheBuilder;
import io.github.reactive.cache.ReactiveCacheLoader;
import io.github.reactive.cache.ReactiveLoadingCache;

import java.util.concurrent.TimeUnit;

/**
 * 值刷新测试
 */
public class RefreshTestMain {

    public static void main(String[] args) throws Exception {
        new RefreshTestMain().main0();
    }

    public void main0() throws Exception {
        ReactiveLoadingCache<String, String> cache = ReactiveCacheBuilder.newBuilder()
                .initialCapacity(2048)
                .maximumSize(2048)
                .concurrencyLevel(128)
                .expireAfterWrite(6000, TimeUnit.SECONDS)
                .refreshAfterWrite(1, TimeUnit.SECONDS)
                .build(ReactiveCacheLoader.from(this::loadValue));

        cache.get("a").subscribe();

        TimeUnit.SECONDS.sleep(2);

        cache.get("a").subscribe();

        TimeUnit.SECONDS.sleep(2);

        cache.get("a").subscribe();

        TimeUnit.SECONDS.sleep(1000);

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
