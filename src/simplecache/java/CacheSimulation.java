import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A small deterministic simulation of a TTL cache. This is the engine; the tests that use it are
 * {@code OriginalCacheSimulationTest} (does the reviewed cache hold up?) and {@code SimulationTrustTest} (can the
 * simulation itself be trusted?).
 *
 * <h2>What one run does</h2>
 * {@link #simulate} builds a fresh world for one seed: a {@link VirtualClock} and a cache. It then performs a random
 * sequence of operations drawn only from {@code Random(seed)}, so a seed replays exactly:
 * <ul>
 *   <li>{@code put(key, value)}: write a unique value;</li>
 *   <li>{@code get(key)}: read, then check the answer against the reference model;</li>
 *   <li>{@code advance(ms)}: move true time forward, often landing exactly on, or one millisecond before, the TTL;</li>
 *   <li>{@code stepWallClock(+-ms)}: move only the wall clock (an NTP correction, a VM resume) - the injected fault;</li>
 *   <li>{@code evictExpired()}: run cleanup (a no-op for the reviewed cache, which has none).</li>
 * </ul>
 * Keys come from a pool of {@value #KEY_POOL}, so operations keep colliding on the same entries.
 *
 * <h2>The reference model</h2>
 * Deliberately tiny and independent of the cache's logic: for each key, the latest value written and the TRUE time it
 * was written. It cannot share the cache's bugs because it never reads the wall clock.
 *
 * <h2>The invariants, checked against the model</h2>
 * <ul>
 *   <li>{@code NeverServesExpired}: no value is returned once its true age reaches the TTL.</li>
 *   <li>{@code NoPrematureExpiry}: a value whose true age is below the TTL is returned.</li>
 *   <li>{@code ReturnsLatestPut}: a returned value is the most recent one written for that key.</li>
 *   <li>{@code ServesUnknownValue}: nothing is returned for a key never written.</li>
 *   <li>{@code NoLeakAtQuiescence}: after enough time passes and cleanup runs, nothing is stored.</li>
 * </ul>
 *
 * <h2>Real vs modelled</h2>
 * Real: the cache logic ({@link ClockedSimpleCache} is the reviewed code with an injected clock and TTL;
 * {@link CorrectedCache} is the sketched fix). Modelled: time and the reference model. Not modelled: real threads (see
 * the directed stampede test), memory limits, GC, real NTP behaviour.
 */
final class CacheSimulation {

    static final long TTL_MS = 60_000;
    static final long TTL_NANOS = TTL_MS * 1_000_000L;
    static final int KEY_POOL = 8;
    static final int SEEDS = 300;

    /** States the generator must actually reach; checked by {@code SimulationTrustTest}. */
    static final Set<String> DECLARED_SOMETIMES = Set.of(
        "stepBackWhileEntryLive", "stepForwardWhileEntryLive", "getAtTtlBoundary", "overwriteLiveKey", "maintenanceWithExpiredEntries");

    interface CacheFactory {
        TtlCache<Integer, String> create(VirtualClock clock);
    }

    /** The reviewed cache, with only an injected clock and TTL added. */
    static final CacheFactory ORIGINAL = clock -> new ClockedSimpleCache<>(clock::wallMillis, TTL_MS);

    /** The fixed sketch: monotonic clock, conditional removal, cleanup, null rejection. */
    static final CacheFactory CORRECTED = clock -> new CorrectedCache<>(clock::monotonicNanos, TTL_NANOS);

    record Violation(String invariant, int step, String detail) {
    }

    static final class Run {
        final long seed;
        final List<String> trace = new ArrayList<>();
        final List<Violation> violations = new ArrayList<>();
        final Set<String> reached = new LinkedHashSet<>();

        Run(long seed) {
            this.seed = seed;
        }

        Set<String> violatedInvariants() {
            Set<String> ids = new LinkedHashSet<>();
            for (Violation v : violations) ids.add(v.invariant());
            return ids;
        }
    }

    private CacheSimulation() {
    }

    // ---- one seed ----

    static Run simulate(long seed, CacheFactory factory) {
        Run run = new Run(seed);
        Random random = new Random(seed); // the ONLY source of randomness
        VirtualClock clock = new VirtualClock();
        TtlCache<Integer, String> cache = factory.create(clock);

        Map<Integer, String> latestValue = new HashMap<>();
        Map<Integer, Long> writtenAtTrueMs = new HashMap<>();
        int operations = 8 + random.nextInt(50); // short runs: the smallest failing seed is then easy to read

        for (int step = 0; step < operations; step++) {
            int pick = random.nextInt(100);
            if (pick < 30) {
                int key = random.nextInt(KEY_POOL);
                String value = "v" + seed + "-" + step; // unique, so a stale answer is detectable
                if (isLive(key, latestValue, writtenAtTrueMs, clock)) run.reached.add("overwriteLiveKey");
                cache.put(key, value);
                latestValue.put(key, value);
                writtenAtTrueMs.put(key, clock.trueMillis());
                run.trace.add(line(step, clock, "put(" + key + ", " + value + ")"));
            } else if (pick < 65) {
                int key = random.nextInt(KEY_POOL);
                String actual = cache.get(key);
                run.trace.add(line(step, clock, "get(" + key + ") -> " + actual));
                checkGet(run, step, key, actual, latestValue, writtenAtTrueMs, clock);
            } else if (pick < 80) {
                long ms = switch (random.nextInt(4)) {
                    case 0 -> 1_000L * (1 + random.nextInt(10));
                    case 1 -> TTL_MS;        // lands exactly on the boundary
                    case 2 -> TTL_MS - 1;    // one millisecond before it
                    default -> TTL_MS + 1 + random.nextInt(60_000);
                };
                clock.advance(ms);
                run.trace.add(line(step, clock, "advance(" + ms + " ms)"));
            } else if (pick < 88) {
                long magnitude = 1_000L * (1 + random.nextInt(7_200));
                boolean back = random.nextBoolean();
                if (anyLive(latestValue, writtenAtTrueMs, clock)) {
                    run.reached.add(back ? "stepBackWhileEntryLive" : "stepForwardWhileEntryLive");
                }
                clock.stepWall(back ? -magnitude : magnitude);
                run.trace.add(line(step, clock, "stepWallClock(" + (back ? "-" : "+") + magnitude + " ms)"));
            } else {
                boolean anyExpired = writtenAtTrueMs.values().stream().anyMatch(w -> clock.trueMillis() - w >= TTL_MS);
                if (anyExpired) run.reached.add("maintenanceWithExpiredEntries");
                cache.evictExpired();
                run.trace.add(line(step, clock, "evictExpired()"));
            }
        }

        // quiescent end state: let everything expire, run cleanup, then nothing may remain stored
        clock.advance(2 * TTL_MS);
        cache.evictExpired();
        run.trace.add(line(operations, clock, "quiesce: advance 2*TTL, evictExpired(), size() = " + cache.size()));
        if (cache.size() != 0) {
            run.violations.add(new Violation("NoLeakAtQuiescence", operations,
                cache.size() + " entries still stored after everything expired and cleanup ran"));
        }
        return run;
    }

    private static void checkGet(Run run, int step, int key, String actual, Map<Integer, String> latestValue,
                                 Map<Integer, Long> writtenAtTrueMs, VirtualClock clock) {
        boolean known = latestValue.containsKey(key);
        long trueAge = known ? clock.trueMillis() - writtenAtTrueMs.get(key) : -1;
        if (known && (trueAge == TTL_MS || trueAge == TTL_MS - 1)) run.reached.add("getAtTtlBoundary");

        if (actual != null) {
            if (!known) {
                run.violations.add(new Violation("ServesUnknownValue", step, "key " + key + " was never written"));
            } else if (trueAge >= TTL_MS) {
                run.violations.add(new Violation("NeverServesExpired", step,
                    "key " + key + " served at true age " + trueAge + " ms, TTL is " + TTL_MS + " ms"));
            } else if (!actual.equals(latestValue.get(key))) {
                run.violations.add(new Violation("ReturnsLatestPut", step,
                    "key " + key + " returned " + actual + ", latest put is " + latestValue.get(key)));
            }
        } else if (known && trueAge < TTL_MS) {
            run.violations.add(new Violation("NoPrematureExpiry", step,
                "key " + key + " missing at true age " + trueAge + " ms, TTL is " + TTL_MS + " ms"));
        }
    }

    private static boolean isLive(int key, Map<Integer, String> latestValue, Map<Integer, Long> writtenAtTrueMs, VirtualClock clock) {
        return latestValue.containsKey(key) && clock.trueMillis() - writtenAtTrueMs.get(key) < TTL_MS;
    }

    private static boolean anyLive(Map<Integer, String> latestValue, Map<Integer, Long> writtenAtTrueMs, VirtualClock clock) {
        for (Integer key : latestValue.keySet()) {
            if (isLive(key, latestValue, writtenAtTrueMs, clock)) return true;
        }
        return false;
    }

    private static String line(int step, VirtualClock clock, String what) {
        return String.format("  step %3d | true t=%8d ms | wall offset %+8d ms | %s",
            step, clock.trueMillis(), clock.wallMillis() - 1_700_000_000_000L - clock.trueMillis(), what);
    }

    // ---- many seeds ----

    static List<Run> sweep(CacheFactory factory) {
        List<Run> runs = new ArrayList<>();
        for (long seed = 0; seed < SEEDS; seed++) runs.add(simulate(seed, factory));
        return runs;
    }

    static Set<String> violatedAnywhere(List<Run> runs) {
        Set<String> ids = new LinkedHashSet<>();
        for (Run run : runs) ids.addAll(run.violatedInvariants());
        return ids;
    }

    /** The seed with the shortest trace that violates the invariant: the easiest one to read. */
    static Run smallestFailingRun(List<Run> runs, String invariant) {
        Run best = null;
        for (Run run : runs) {
            if (run.violatedInvariants().contains(invariant) && (best == null || run.trace.size() < best.trace.size())) best = run;
        }
        return best;
    }

    /** Human-readable account of one violation: invariant, seed to replay, what went wrong, and the trace up to it. */
    static String describe(String title, Run run, String invariant, long failingSeeds) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(title).append("\n");
        sb.append("Invariant ").append(invariant).append(" is violated in ").append(failingSeeds).append(" of ").append(SEEDS).append(" seeds.\n");
        sb.append("Smallest failing seed: ").append(run.seed).append("   (replay: ./gradlew cacheProblems -Dsim.seed=").append(run.seed).append(")\n");
        for (Violation v : run.violations) {
            if (v.invariant().equals(invariant)) sb.append("  at step ").append(v.step()).append(": ").append(v.detail()).append("\n");
        }
        int firstViolationStep = run.violations.stream().filter(v -> v.invariant().equals(invariant)).mapToInt(Violation::step).min().orElse(run.trace.size() - 1);
        run.trace.stream().limit(firstViolationStep + 1L).forEach(line -> sb.append(line).append("\n"));
        return sb.toString();
    }
}
