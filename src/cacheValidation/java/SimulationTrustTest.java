import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>These all PASS. They show the simulation can be trusted.</h1>
 *
 * {@code OriginalCacheSimulationTest} reports bugs in the reviewed cache. A bug report is only worth reading if the
 * tool that produced it is sound. These tests check the tool, and never touch the reviewed cache for a verdict:
 *
 * <ul>
 *   <li>the invariants are satisfiable: a fixed cache passes them on every seed;</li>
 *   <li>the invariants can see faults: deliberately broken variants of the fixed cache are caught;</li>
 *   <li>the harness is sound: seeds replay identically, and the interesting states are actually reached.</li>
 * </ul>
 *
 * <b>Expected result: 6 passing, 0 failing.</b> Run with {@code ./gradlew cacheValidation}.
 */
class SimulationTrustTest {

    private static final CacheSimulation.CacheFactory CORRECTED = CacheSimulation.CORRECTED;

    /**
     * The invariants are satisfiable. A cache with a monotonic clock, scheduled cleanup, conditional removal and null
     * rejection violates nothing on any of the 300 seeds, so the three failing tests against the original are not
     * asking for the impossible.
     */
    @Test
    void aCorrectedCachePassesEveryInvariantOnEverySeed() {
        for (CacheSimulation.Run run : CacheSimulation.sweep(CORRECTED)) {
            if (!run.violations.isEmpty()) {
                String invariant = run.violations.get(0).invariant();
                fail(CacheSimulation.describe("corrected cache failed", run, invariant, 1));
            }
        }
    }

    /**
     * The invariants can see faults (1 of 3). The corrected design, but reading the wall clock: {@code NeverServesExpired}
     * must notice.
     */
    @Test
    void mutantOnTheWallClockIsCaughtByNeverServesExpired() {
        CacheSimulation.CacheFactory mutant = clock ->
            new CorrectedCache<>(() -> clock.wallMillis() * 1_000_000L, CacheSimulation.TTL_NANOS);
        assertTrue(CacheSimulation.violatedAnywhere(CacheSimulation.sweep(mutant)).contains("NeverServesExpired"));
    }

    /**
     * The invariants can see faults (2 of 3). The corrected design, but cleanup does nothing: {@code NoLeakAtQuiescence}
     * must notice.
     */
    @Test
    void mutantWithoutCleanupIsCaughtByNoLeakAtQuiescence() {
        CacheSimulation.CacheFactory mutant = clock -> new TtlCache<>() {
            private final TtlCache<Integer, String> inner = CORRECTED.create(clock);

            public void put(Integer key, String value) {
                inner.put(key, value);
            }

            public String get(Integer key) {
                return inner.get(key);
            }

            public int size() {
                return inner.size();
            }

            public void evictExpired() {
            }
        };
        assertTrue(CacheSimulation.violatedAnywhere(CacheSimulation.sweep(mutant)).contains("NoLeakAtQuiescence"));
    }

    /**
     * The invariants can see faults (3 of 3). The corrected design, but keeping the first value ever written:
     * {@code ReturnsLatestPut} must notice. This invariant passes on the original, so this is the only place it is
     * shown to work.
     */
    @Test
    void mutantThatIgnoresOverwritesIsCaughtByReturnsLatestPut() {
        CacheSimulation.CacheFactory mutant = clock -> new TtlCache<>() {
            private final TtlCache<Integer, String> inner = CORRECTED.create(clock);

            public void put(Integer key, String value) {
                if (inner.get(key) == null) inner.put(key, value);
            }

            public String get(Integer key) {
                return inner.get(key);
            }

            public int size() {
                return inner.size();
            }

            public void evictExpired() {
                inner.evictExpired();
            }
        };
        assertTrue(CacheSimulation.violatedAnywhere(CacheSimulation.sweep(mutant)).contains("ReturnsLatestPut"));
    }

    /**
     * The harness is sound (1 of 2). The same seed gives the same trace and the same violations every time, which is
     * what makes "replay with -Dsim.seed=N" trustworthy. It would fail if the simulation used any outside randomness or
     * the real clock.
     */
    @Test
    void theSameSeedReplaysIdentically() {
        for (long seed = 0; seed < 25; seed++) {
            CacheSimulation.Run first = CacheSimulation.simulate(seed, CacheSimulation.ORIGINAL);
            CacheSimulation.Run second = CacheSimulation.simulate(seed, CacheSimulation.ORIGINAL);
            assertEquals(first.trace, second.trace, "seed " + seed + " must replay identically");
            assertEquals(first.violations, second.violations);
        }
    }

    /**
     * The harness is sound (2 of 2). Every rare state the generator declares (a clock step back or forward while an
     * entry is live, a read exactly at the TTL boundary, an overwrite of a live key, cleanup with expired entries) is
     * reached by at least one seed. Without this, a sweep could pass by never visiting the interesting states.
     */
    @Test
    void theGeneratorReachesTheRareStatesItDeclares() {
        Set<String> reached = new LinkedHashSet<>();
        List<CacheSimulation.Run> runs = CacheSimulation.sweep(CacheSimulation.ORIGINAL);
        for (CacheSimulation.Run run : runs) reached.addAll(run.reached);
        Set<String> missing = new LinkedHashSet<>(CacheSimulation.DECLARED_SOMETIMES);
        missing.removeAll(reached);
        assertTrue(missing.isEmpty(), "never reached: " + missing);
    }
}
