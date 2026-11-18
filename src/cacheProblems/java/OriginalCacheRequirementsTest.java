import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>Requirements the reviewed cache does not meet: each failing test is a finding</h1>
 *
 * Each test states one thing a production cache must do (the test name) and runs it against the reviewed
 * {@link SimpleCache}. The failure message says what the cache did instead. There is one test per issue in
 * {@code SimpleCache.md}; the test javadoc names the issue number and whether production can reach it.
 *
 * <ul>
 *   <li><b>Expected result: 8 failing tests, 0 passing.</b> Run with {@code ./gradlew cacheProblems}.</li>
 *   <li>The same requirements are run against a fixed sketch in {@code CorrectedSketchTest}, where they pass. That
 *       is what shows the failures are the cache's fault and not the test's.</li>
 *   <li>Time is virtual ({@link VirtualClock}), so nothing sleeps and results never depend on timing.</li>
 * </ul>
 */
class OriginalCacheRequirementsTest {

    private static final long TTL_MS = 60_000;

    private static ClockedSimpleCache<Integer, String> original(VirtualClock clock) {
        return new ClockedSimpleCache<>(clock::wallMillis, TTL_MS);
    }

    /**
     * <b>Issue 1 and 6.</b> Expired entries must be freed. Production reachable: yes, always; any key not read again
     * after it expires stays in the heap forever.
     */
    @Test
    void expiredEntriesAreFreed() {
        VirtualClock clock = new VirtualClock();
        ClockedSimpleCache<Integer, String> cache = original(clock);
        for (int i = 0; i < 10_000; i++) cache.put(i, "v" + i);
        clock.advance(TTL_MS + 1);
        for (int i = 0; i < 10_000; i++) assertNull(cache.get(i), "all entries read as expired");

        cache.evictExpired(); // the original has no cleanup at all; this is a no-op for it

        assertEquals(0, cache.size(), "10,000 entries expired a minute ago but are all still stored: nothing ever frees them");
    }

    /**
     * <b>Issue 2.</b> The cache must be bounded. Production reachable: yes; a burst of distinct keys (a scan, a bad
     * client, a bug) grows the heap with no limit.
     */
    @Test
    void sizeIsBounded() {
        SimpleCache<Integer, int[]> cache = new SimpleCache<>();
        int distinctKeys = 500_000;
        int reasonableMaximum = 100_000;
        for (int i = 0; i < distinctKeys; i++) cache.put(i, new int[0]);

        assertTrue(cache.size() <= reasonableMaximum,
            "stored " + cache.size() + " entries after " + distinctKeys + " distinct puts: there is no maximum size");
    }

    /**
     * <b>Issue 3.</b> A value older than the TTL must never be served, even if the wall clock was corrected backwards.
     * Production reachable: yes but rare (NTP step, manual change, VM resume or migration). Not a daily incident.
     */
    @Test
    void entryOlderThanTtlIsNeverServed() {
        VirtualClock clock = new VirtualClock();
        ClockedSimpleCache<Integer, String> cache = original(clock);
        cache.put(1, "old");

        clock.stepWall(-3 * 3_600_000L); // wall clock corrected back by 3 hours
        clock.advance(2 * 3_600_000L);   // 2 hours really pass, TTL is 1 minute

        assertNull(cache.get(1), "an entry written 2 hours ago was served; TTL is 1 minute");
    }

    /**
     * <b>Issue 3.</b> A live value must not expire early because the wall clock jumped forward. Production reachable:
     * yes but rare. A forward step makes every live entry expire at once, a self-inflicted avalanche.
     */
    @Test
    void liveEntryIsNotExpiredByAClockStep() {
        VirtualClock clock = new VirtualClock();
        ClockedSimpleCache<Integer, String> cache = original(clock);
        cache.put(1, "fresh");

        clock.stepWall(3_600_000); // no real time passed

        assertEquals("fresh", cache.get(1), "an entry written 0 ms ago already reads as expired");
    }

    /**
     * <b>Issue 4.</b> When a hot key misses, the backend must be called once, not once per thread. Production
     * reachable: yes. This tests the get / load / put pattern a plain get/put cache forces on its callers, not a defect
     * inside one method. A barrier after the miss makes the outcome deterministic, with no sleeps or timing luck: it
     * opens only when all threads have already missed.
     */
    @Test
    void hotKeyIsLoadedOnce() throws Exception {
        int threads = 50;
        SimpleCache<String, String> cache = new SimpleCache<>();
        AtomicInteger backendLoads = new AtomicInteger();
        CyclicBarrier allHaveMissed = new CyclicBarrier(threads);

        runConcurrently(threads, () -> {
            if (cache.get("hot") == null) {
                allHaveMissed.await(10, TimeUnit.SECONDS);
                backendLoads.incrementAndGet(); // the expensive database / downstream call
                cache.put("hot", "loaded");
            }
        });

        assertEquals(1, backendLoads.get(), threads + " threads missed the same key and all of them called the backend");
    }

    /**
     * <b>Issue 5.</b> A value written while an expired entry is being removed must survive. This targets the
     * <i>obvious fix</i> for issue 1 (remove on expiry), not the reviewed class, which never removes anything.
     * Production reachable: only if someone applies that fix. A hook between the expiry check and the remove forces
     * the interleaving, so there is no timing luck. This is why review issue 5 exists.
     */
    @Test
    void aValueWrittenDuringExpiryRemovalSurvives_whenTheNaiveFixIsApplied() {
        VirtualClock clock = new VirtualClock();
        NaiveRemoveOnExpiryCache cache = new NaiveRemoveOnExpiryCache(clock);
        cache.put("k", "old");
        clock.advance(TTL_MS + 1);
        cache.betweenCheckAndRemove = () -> cache.put("k", "fresh"); // another thread writes right here

        assertNull(cache.get("k"), "the old value is expired, so this read is a miss either way");
        cache.betweenCheckAndRemove = () -> { };

        assertEquals("fresh", cache.get("k"), "the value written during the removal was deleted by the unconditional remove");
    }

    /**
     * <b>Issue 7.</b> "Nothing found" must be cacheable, or at least distinguishable from a miss. Production reachable:
     * yes, for any caller that caches a lookup which can legitimately return nothing.
     */
    @Test
    void anAbsentResultCanBeCached() {
        SimpleCache<String, String> cache = new SimpleCache<>();
        AtomicInteger backendLoads = new AtomicInteger();
        for (int call = 0; call < 3; call++) {
            if (cache.get("not-in-database") == null) {
                backendLoads.incrementAndGet();
                cache.put("not-in-database", null); // "cache the fact that there is nothing"
            }
        }

        assertEquals(1, backendLoads.get(), "a null value is indistinguishable from a miss, so the backend was asked on every call");
    }

    /**
     * <b>Issue 9.</b> Callers must not share mutable cached state. Production reachable: yes, whenever the value type
     * is mutable.
     */
    @Test
    void callersDoNotShareMutableState() {
        SimpleCache<String, List<String>> cache = new SimpleCache<>();
        cache.put("k", new ArrayList<>());

        List<String> seenByThreadA = cache.get("k");
        List<String> seenByThreadB = cache.get("k");
        seenByThreadA.add("written by A, no synchronization");

        assertTrue(seenByThreadB.isEmpty(), "thread B sees thread A's write: both got the same mutable object");
    }

    // ---- helpers ----

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void runConcurrently(int threads, ThrowingRunnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    task.run();
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** What most people would write first to fix issue 1: remove an expired entry when it is read. */
    private static final class NaiveRemoveOnExpiryCache {
        private static final class Entry {
            final String value;
            final long writtenAtMs;

            Entry(String value, long writtenAtMs) {
                this.value = value;
                this.writtenAtMs = writtenAtMs;
            }
        }

        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        private final VirtualClock clock;
        Runnable betweenCheckAndRemove = () -> { };

        NaiveRemoveOnExpiryCache(VirtualClock clock) {
            this.clock = clock;
        }

        void put(String key, String value) {
            map.put(key, new Entry(value, clock.trueMillis()));
        }

        String get(String key) {
            Entry entry = map.get(key);
            if (entry == null) return null;
            if (clock.trueMillis() - entry.writtenAtMs < TTL_MS) return entry.value;
            betweenCheckAndRemove.run();
            map.remove(key); // BUG: removes whatever is there now, not the entry that was checked
            return null;
        }
    }
}
