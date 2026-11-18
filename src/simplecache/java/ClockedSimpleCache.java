import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * {@link SimpleCache} with exactly two changes, so tests can control time:
 * <ol>
 *   <li>{@code System.currentTimeMillis()} becomes the injected {@code wallMillis} supplier;</li>
 *   <li>the TTL is a constructor argument instead of a constant.</li>
 * </ol>
 * The logic of {@code put}, {@code get} and {@code size} is otherwise identical, including the bugs.
 * (Why a copy at all: the original hard-codes the clock and inlines its TTL constant, which is review item 11.)
 */
final class ClockedSimpleCache<K, V> implements TtlCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final LongSupplier wallMillis;
    private final long ttlMs;

    ClockedSimpleCache(LongSupplier wallMillis, long ttlMs) {
        this.wallMillis = wallMillis;
        this.ttlMs = ttlMs;
    }

    private static final class CacheEntry<V> {
        final V value;
        final long timestamp;

        CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, wallMillis.getAsLong()));
    }

    @Override
    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (wallMillis.getAsLong() - entry.timestamp < ttlMs) {
                return entry.value;
            }
        }
        return null;
    }

    @Override
    public int size() {
        return cache.size();
    }

    /** The original has no cleanup of any kind, so there is nothing to call. */
    @Override
    public void evictExpired() {
    }
}
