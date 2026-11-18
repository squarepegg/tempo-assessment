import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A sketch of the fixes for review items 1, 3, 4, 5 and 7. It exists so the simulation can show that the invariants
 * which fail on {@link SimpleCache} pass on a corrected design. It is not a drop-in production class: there is no size
 * bound (item 2), no TTL jitter, no metrics. In production, use Caffeine (see SimpleCache.md).
 */
final class CorrectedCache<K, V> implements TtlCache<K, V> {
    private static final class Entry<V> {
        final V value;
        final long writtenAtNanos;

        Entry(V value, long writtenAtNanos) {
            this.value = value;
            this.writtenAtNanos = writtenAtNanos;
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final long ttlNanos;

    /** Test hook: runs after an expired entry was seen and before it is removed, to force an interleaving. */
    Runnable beforeConditionalRemove = () -> { };

    CorrectedCache(LongSupplier nanoTime, long ttlNanos) {
        this.nanoTime = nanoTime;
        this.ttlNanos = ttlNanos;
    }

    private boolean isExpired(Entry<V> entry) {
        return nanoTime.getAsLong() - entry.writtenAtNanos >= ttlNanos; // monotonic clock (item 3)
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(value, "null values are rejected so a null result always means 'absent'"); // item 7
        map.put(key, new Entry<>(value, nanoTime.getAsLong()));
    }

    @Override
    public V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) return null;
        if (!isExpired(entry)) return entry.value;
        beforeConditionalRemove.run();
        map.remove(key, entry); // conditional: never removes a newer entry stored in the meantime (item 5)
        return null;
    }

    /** Single-flight load: concurrent callers for the same key wait for one load instead of each running it (item 4). */
    V getOrLoad(K key, Function<K, V> loader) {
        return map.compute(key, (k, current) ->
            current != null && !isExpired(current)
                ? current
                : new Entry<>(Objects.requireNonNull(loader.apply(k), "loader returned null"), nanoTime.getAsLong())
        ).value;
    }

    @Override
    public int size() {
        return map.size();
    }

    /** Scheduled or manual cleanup so keys that are never read again are still freed (item 1). */
    @Override
    public void evictExpired() {
        map.entrySet().removeIf(e -> isExpired(e.getValue()));
    }
}
