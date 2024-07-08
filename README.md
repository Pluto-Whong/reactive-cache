# reactive-cache

基于reactive框架响应式链路的可刷新缓存

## 概要

整体框架是基于 guava 的 Cache 能力，所具备的特性也是和 guava 是一致的，主要是解决 guava 缓存的 refresh 机制与 reactor
的结合能力。

1. 在使用 #get(K k) 方法获取值时会返回Mono对象，可以通过反应式链进行处理，
   等价 guava 中的 #get(K k) 在不存在值时进行阻塞等待、有值时直接返回的使用方法；
2. 在需要提前刷新的情况下，可以通过默认 loader 方法进行异步刷新
3. 在刷新过程，若其他线程获取值时不存在或值失效而并发获取值时，会通过 waitForLoadingValue
   方法等待第一个取值的线程进行返回（即保障同一key只有一个线程在刷新值）
4. 在实际获取时若遇到报错、返回值为null时，会驱逐key缓存，从而让保障新线程去刷新值，避免偶发异常而导致需要等待缓存失效时才能去刷新的问题（下面会举一个
   Mono#cache 使用方式的例子，存在偶发失败时异常会长时间被缓存的问题）
5. 其他常规参数如：maximumSize、concurrencyLevel、recordStats、softValues、weakKeys、weakValues、expireAfterWrite、refreshAfterWrite
   参数机制都进行了保留，额外增加了：
   > 1. loadingTimeout（加载值超时，避免超长时间等待的问题，默认0不超时）；
   > 2. timeoutScheduler（结合 loadingTimeout 使用，因 reactor 事件切换是靠 Scheduler 实现的）；
   > 3. loadingRestartScheduler（这是重写的策略，也是实现 refresh 与 reactor 结合的重点实现方法；
        waitForLoadingValue 的实现是靠 Mono.create(sink) 保存sink，在加载完成时 调用 sink.success(v) 或 sink.error(e) 实现；
        这两个方法调用时，若没有使用 另外的线程调用 会在当前线程执行， 则可能会出现主加载线程被长时间饥饿，且其他的并发等待的线程会成为有序加载）

## 使用方法

### reactive-cache 的使用方法

```java
import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import reactor.core.publisher.Mono;
import top.plutoppppp.reactive.cache.ReactiveCacheBuilder;
import top.plutoppppp.reactive.cache.ReactiveCacheLoader;
import top.plutoppppp.reactive.cache.ReactiveLoadingCache;
import top.plutoppppp.reactive.cache.exception.InvalidCacheLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
```

### 对比一个非响应式的 guava-cache 用法

```java
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuavaSimpleDemo {

    private static final Logger log = LoggerFactory.getLogger(GuavaSimpleDemo.class);

    // 代表空值对象，这里我们是用String模拟的，所以还请理解自动转义为你要返回的对象
    public static final String EMPTY_VALUE = "";

    public static Map<String, String> MOCK_MAP = new HashMap<>();

    public static void main(String[] args) {
        LoadingCache<String, String> cache = CacheBuilder.newBuilder()
                .initialCapacity(256)
                .maximumSize(256)
                .concurrencyLevel(16)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(50, TimeUnit.SECONDS)
                .recordStats()
                .build(CacheLoader.from(GuavaSimpleDemo::loadValue));

        MOCK_MAP.put("a", "1");
        MOCK_MAP.put("b", "2");
        MOCK_MAP.put("c", "3");
        MOCK_MAP.put("exception", "i'm fine");
        MOCK_MAP.put("demo", "demoValue");

        String a = cache.getUnchecked("a");
        // 将会输出 1
        System.out.println(a);

        String b = cache.getUnchecked("b");
        int b2 = Integer.parseInt(a) + 100;
        // 将会输出 102
        System.out.println(b2);

        String z;
        try {
            z = cache.getUnchecked("z");
        } catch (CacheLoader.InvalidCacheLoadException e) {
            z = "empty";
        }
        // 将会输出 empty
        System.out.println(z);


        String exception;
        try {
            exception = cache.get("exception");
        } catch (Throwable e) {
            e.printStackTrace();
            exception = "i hava a exception";
        }
        // 这里将会输出 i hava a exception
        System.out.println(exception);

        try {
            exception = cache.get("exception");
        } catch (Throwable e) {
            e.printStackTrace();
            exception = "i hava a exception";
        }
        // 这里将会输出 i'm fine
        System.out.println(exception);


        // 常见的用法
        String demo;
        try {
            demo = cache.get("demo");
        } catch (CacheLoader.InvalidCacheLoadException e) {
            log.warn("this map a empty value", e);
            demo = null;
        } catch (Throwable e) {
            log.error("that have a exception, give up", e);
            demo = null;
        }

        if (Objects.isNull(demo)) {
            demo = EMPTY_VALUE;
        }

        if (Objects.equals(demo, EMPTY_VALUE)) {
            // 无值的处理过程
            demo = "empty, give up";
        }
        // 有值时处理过程
        demo = "success";
        // 这里将会输出 success
        System.out.println(demo);

    }

    private static final AtomicBoolean firstException = new AtomicBoolean(true);

    public static String loadValue(String key) {
        if ("exception".equals(key)
                && firstException.compareAndSet(true, false)) {
            throw new RuntimeException("that a mock exception");
        }

        return MOCK_MAP.get(key);
    }

}
```

### 再对比一个直接使用guava和Mono.cache结合的方式

```java
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuavaMonoSimpleDemo {

    private static final Logger log = LoggerFactory.getLogger(GuavaMonoSimpleDemo.class);

    // 代表空值对象，这里我们是用String模拟的，所以还请理解自动转义为你要返回的对象
    public static final String EMPTY_VALUE = "";

    public static Map<String, String> MOCK_MAP = new HashMap<>();

    public static void main(String[] args) {
        LoadingCache<String, Mono<String>> cache = CacheBuilder.newBuilder()
                .initialCapacity(256)
                .maximumSize(256)
                .concurrencyLevel(16)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(50, TimeUnit.SECONDS)
                .recordStats()
                .build(CacheLoader.from(GuavaMonoSimpleDemo::loadValue));

        MOCK_MAP.put("a", "1");
        MOCK_MAP.put("b", "2");
        MOCK_MAP.put("c", "3");
        MOCK_MAP.put("exception", "i'm fine");
        MOCK_MAP.put("demo", "demoValue");

        cache.getUnchecked("a")
                .subscribe(v -> {
                    // 将会输出 1
                    System.out.println(v);
                });

        cache.getUnchecked("b")
                .flatMap(v ->
                        Mono.just(Integer.parseInt(v) + 100)
                )
                .subscribe(v -> {
                    // 将会输出 102
                    System.out.println(v);
                });

        cache.getUnchecked("z")
                // 因为使用了Mono返回，他的定义是不为null的，所以会返回值，但是自己的业务反应链在执行时接收到的值却是null值
//                .onErrorResume(CacheLoader.InvalidCacheLoadException.class, e -> Mono.just("empty"))
                .defaultIfEmpty("empty")
                .subscribe(v -> {
                    // 将会输出 empty
                    System.out.println(v);
                });

        cache.getUnchecked("exception")
                // 第一次调用，模拟报错，会进行异常栈输出
                .onErrorResume(RuntimeException.class, e -> {
                    e.printStackTrace();
                    return Mono.just("i hava a exception");
                })
                .subscribe(v -> {
                    // 这里将会输出 i hava a exception
                    System.out.println(v);
                });

        cache.getUnchecked("exception")
                // 第二次调用，因为使用了 cache 方法，所以这里依然会报错，必须要等刷新时间或过期时间到达才会再次刷新
                .onErrorResume(RuntimeException.class, e -> {
                    e.printStackTrace();
                    return Mono.just("i hava a exception");
                })
                .subscribe(v -> {
                    // 这里将会输出 i'm fine
                    System.out.println(v);
                });


        // 常见的用法
        cache.getUnchecked("demo")
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
        return Mono.defer(() -> {
                    if ("exception".equals(key)
                            && firstException.compareAndSet(true, false)) {
                        throw new RuntimeException("that a mock exception");
                    }

                    return Mono.justOrEmpty(MOCK_MAP.get(key));
                })
                //若没有这个cache，每次获取值时，都会执行上游链路，也就是每次都会到实际服务取值
                .cache();
    }

}
```
