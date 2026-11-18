/**
 * The few operations the simulation needs from any cache under test.
 *
 * <p>{@link #evictExpired()} is the "maintenance" operation: a scheduled sweeper or a manual call in real life.
 * The cache from the task has no such method, so its adapter makes it a no-op. That is the point of review item 1.
 */
interface TtlCache<K, V> {
    void put(K key, V value);

    V get(K key);

    /** Number of entries physically stored, live or expired. */
    int size();

    void evictExpired();
}
