import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import reactor.core.publisher.Mono;
import io.github.reactive.cache.ReactiveCacheBuilder;
import io.github.reactive.cache.ReactiveCacheLoader;
import io.github.reactive.cache.ReactiveLoadingCache;
import io.github.reactive.cache.exception.InvalidCacheLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 简单的demo
 */
public class SimpleDemo {

    private static final Logger log = LoggerFactory.getLogger(SimpleDemo.class);

    // 代表空值对象，这里我们是用String模拟的，所以还请理解自动转义为你要返回的对象
    public static final String EMPTY_VALUE = "";

    public static Map<String, String> MOCK_MAP = new HashMap<>();

    public static void main(String[] args) {
        ReactiveLoadingCache<String, String> cache = ReactiveCacheBuilder.newBuilder()
                .initialCapacity(256)
                .maximumSize(256)
                .concurrencyLevel(16)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(50, TimeUnit.SECONDS)
                .recordStats()
                .build(ReactiveCacheLoader.from(SimpleDemo::loadValue));

        MOCK_MAP.put("a", "1");
        MOCK_MAP.put("b", "2");
        MOCK_MAP.put("c", "3");
        MOCK_MAP.put("exception", "i'm fine");
        MOCK_MAP.put("demo", "demoValue");

        cache.get("a")
                // 这个 subscribe 只是演示用，会在当前线程执行，
                // 实际情况根据自己的框架实现，而非必须手动调用 subscribe
                // 我想用 reactor 的开发者应该很容易理解我想表达的意思 🤪
                .subscribe(v -> {
                    // 将会输出 1
                    System.out.println(v);
                });

        cache.get("b")
                .flatMap(v ->
                        Mono.just(Integer.parseInt(v) + 100)
                )
                .subscribe(v -> {
                    // 将会输出 102
                    System.out.println(v);
                });

        cache.get("z")
                // 我们缓存是不允许空白值出现的，这里也保留了 guava 的处理方法，会报错 InvalidCacheLoadException
                .onErrorResume(InvalidCacheLoadException.class, e -> Mono.just("empty"))
                .subscribe(v -> {
                    // 将会输出 empty
                    System.out.println(v);
                });

        cache.get("exception")
                // 第一次调用，模拟报错，会进行异常栈输出
                .onErrorResume(RuntimeException.class, e -> {
                    e.printStackTrace();
                    return Mono.just("i hava a exception");
                })
                .subscribe(v -> {
                    // 这里将会输出 i hava a exception
                    System.out.println(v);
                });

        cache.get("exception")
                // 第二次调用，模拟的地方不会报错了，
                .onErrorResume(RuntimeException.class, e -> {
                    e.printStackTrace();
                    return Mono.just("i hava a exception");
                })
                .subscribe(v -> {
                    // 这里将会输出 i'm fine
                    System.out.println(v);
                });


        // 常见的用法
        cache.get("demo")
                .onErrorResume(InvalidCacheLoadException.class, e -> {
                    log.warn("this map a empty value", e);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    // 这里的异常根据自己的框架处理，这里只是个例子，主要是对比 InvalidCacheLoadException 异常需要手动处理的，这里的除了 InvalidCacheLoadException 的其他异常
                    log.error("that have a exception, give up", e);
                    return Mono.empty();
                }).defaultIfEmpty(EMPTY_VALUE)
                .flatMap(v -> {
                    if (Objects.equals(v, EMPTY_VALUE)) {
                        // 无值的处理过程
                        return Mono.just("empty, give up");
                    }
                    // 有值时处理过程
                    return Mono.just("success");
                }).subscribe(v -> {
                    // 这里将会输出 success
                    System.out.println(v);
                });

    }

    private static final AtomicBoolean firstException = new AtomicBoolean(true);

    public static Mono<String> loadValue(String key) {
        if ("exception".equals(key)
                && firstException.compareAndSet(true, false)) {
            throw new RuntimeException("that a mock exception");
        }

        return Mono.justOrEmpty(MOCK_MAP.get(key));
    }

}
