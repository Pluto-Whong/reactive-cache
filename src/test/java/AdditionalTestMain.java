import top.plutoppppp.reactive.cache.AdditionalCacheKey;

public class AdditionalTestMain {

    public static void main(String[] args) {
        AdditionalCacheKey<String> key = AdditionalCacheKey.of("demo",
                "av1", "a",
                "av2", "b"
        );

        System.out.println((Object) key.getAddition("av1"));
        System.out.println((Object) key.getAddition("av2"));

        key = AdditionalCacheKey.of4Class("demo",
                "Disposable",
                1
        );

        System.out.println((Object) key.getAddition(String.class));
        System.out.println((Object) key.getAddition(Integer.class));
    }

}
