import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>These all PASS. They show the failures in OriginalCacheRequirementsTest are fair.</h1>
 *
 * Each test here is the same requirement as one in {@code OriginalCacheRequirementsTest} (same name where possible),
 * run against {@link CorrectedCache}, a sketch of the fixes. If a requirement passes here and fails there, the cause
 * is the reviewed cache and not the test, the virtual clock or the threading in the test.
 *
 * <ul>
 *   <li><b>Expected result: 6 passing, 0 failing.</b> Run with {@code ./gradlew cacheValidation}.</li>
 *   <li>Issues 2 (size bound) and 9 (mutable values) have no counterpart: the sketch does not implement them.</li>
 * </ul>
 */
class CorrectedSketchTest {

    private static final long TTL_MS = CacheSimulation.TTL_MS;
    private static final long TTL_NANOS = CacheSimulation.TTL_NANOS;

    private static CorrectedCache<Integer, String> corrected(VirtualClock clock) {
        return new CorrectedCache<>(clock::monotonicNanos, TTL_NANOS);
    }

    /** Pairs with {@code expiredEntriesAreFreed} (issue 1): with a cleanup operation the 10,000 expired entries go. */
    @Test
    void expiredEntriesAreFreed() {
        VirtualClock clock = new VirtualClock();
        CorrectedCache<Integer, String> cache = corrected(clock);
        for (int i = 0; i < 10_000; i++) cache.put(i, "v" + i);
        clock.advance(TTL_MS + 1);
        cache.evictExpired();
        assertEquals(0, cache.size());
    }

    /** Pairs with {@code entryOlderThanTtlIsNeverServed} (issue 3): a backward wall-clock step keeps nothing alive. */
    @Test
    void entryOlderThanTtlIsNeverServed() {
        VirtualClock clock = new VirtualClock();
        CorrectedCache<Integer, String> cache = corrected(clock);
        cache.put(1, "old");

        clock.stepWall(-3 * 3_600_000L);
        clock.advance(2 * 3_600_000L);

        assertNull(cache.get(1));
    }

    /** Pairs with {@code liveEntryIsNotExpiredByAClockStep} (issue 3): a forward wall-clock step expires nothing. */
    @Test
    void liveEntryIsNotExpiredByAClockStep() {
        VirtualClock clock = new VirtualClock();
        CorrectedCache<Integer, String> cache = corrected(clock);
        cache.put(1, "fresh");

        clock.stepWall(3_600_000);

        assertEquals("fresh", cache.get(1));
    }

    /** Pairs with {@code hotKeyIsLoadedOnce} (issue 4): with single-flight loading the 50 threads call the backend once. */
    @Test
    void hotKeyIsLoadedOnce() throws Exception {
        int threads = 50;
        VirtualClock clock = new VirtualClock();
        CorrectedCache<String, String> cache = new CorrectedCache<>(clock::monotonicNanos, TTL_NANOS);
        AtomicInteger backendLoads = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return cache.getOrLoad("hot", key -> {
                        backendLoads.incrementAndGet();
                        return "loaded";
                    });
                }));
            }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, backendLoads.get());
    }

    /**
     * Pairs with {@code aValueWrittenDuringExpiryRemovalSurvives_whenTheNaiveFixIsApplied} (issue 5): with a conditional
     * remove ({@code remove(key, entry)}) the same forced interleaving keeps the fresh value.
     */
    @Test
    void aValueWrittenDuringExpiryRemovalSurvives() {
        VirtualClock clock = new VirtualClock();
        CorrectedCache<String, String> cache = new CorrectedCache<>(clock::monotonicNanos, TTL_NANOS);
        cache.put("k", "old");
        clock.advance(TTL_MS + 1);
        cache.beforeConditionalRemove = () -> cache.put("k", "fresh");

        assertNull(cache.get("k"));
        cache.beforeConditionalRemove = () -> { };

        assertEquals("fresh", cache.get("k"));
    }

    /**
     * Pairs with {@code anAbsentResultCanBeCached} (issue 7), by a different route: the sketch rejects null values, so
     * a null result can only ever mean "absent" and can never be confused with a miss.
     */
    @Test
    void nullValuesAreRejectedSoNullAlwaysMeansAbsent() {
        VirtualClock clock = new VirtualClock();
        assertThrows(NullPointerException.class, () -> corrected(clock).put(1, null));
    }
}
