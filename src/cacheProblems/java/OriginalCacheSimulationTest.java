import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>Simulation invariants the reviewed cache violates: each failing test is a finding</h1>
 *
 * Runs the seeded simulation ({@link CacheSimulation}) against the reviewed cache and asserts that each invariant
 * holds on all 300 seeds. Where it does not, the failure message is a complete bug report: how many seeds break the
 * invariant, the smallest failing seed, and the step-by-step trace that reproduces it.
 *
 * <ul>
 *   <li><b>Expected result: 3 failing, 1 passing, 1 skipped.</b> Run with {@code ./gradlew cacheProblems}.</li>
 *   <li>The 3 failures: {@code neverServesAnExpiredValue}, {@code neverExpiresALiveValueEarly},
 *       {@code everythingExpiredIsEventuallyFreed}.</li>
 *   <li>The 1 pass, {@code returnsTheRightValues}: the cache is correct about values, so the failures are specific
 *       findings and not a simulation that flags everything.</li>
 *   <li>The skip: {@code replaySeedFromProperty} runs only with {@code -Dsim.seed=N}.</li>
 *   <li>Whether the simulation itself can be trusted is tested in {@code SimulationTrustTest}.</li>
 * </ul>
 */
class OriginalCacheSimulationTest {

    /** Fails, with the smallest failing trace in the message, if any seed violates the invariant. */
    private static void assertInvariantHolds(String invariant, String title) {
        List<CacheSimulation.Run> runs = CacheSimulation.sweep(CacheSimulation.ORIGINAL);
        CacheSimulation.Run witness = CacheSimulation.smallestFailingRun(runs, invariant);
        if (witness != null) {
            long failingSeeds = runs.stream().filter(r -> r.violatedInvariants().contains(invariant)).count();
            fail(CacheSimulation.describe(title, witness, invariant, failingSeeds));
        }
    }

    /** Production reachable: yes but rare (wall-clock steps). */
    @Test
    void neverServesAnExpiredValue() {
        assertInvariantHolds("NeverServesExpired", "A value older than the TTL was served after a wall-clock step back");
    }

    /** Production reachable: yes but rare (wall-clock steps). */
    @Test
    void neverExpiresALiveValueEarly() {
        assertInvariantHolds("NoPrematureExpiry", "A live value was expired early by a wall-clock step forward");
    }

    /** Production reachable: yes, always. */
    @Test
    void everythingExpiredIsEventuallyFreed() {
        assertInvariantHolds("NoLeakAtQuiescence", "Expired entries were never freed");
    }

    /** The one that PASSES, on purpose: the original always returns the latest value and never invents one. */
    @Test
    void returnsTheRightValues() {
        Set<String> violated = CacheSimulation.violatedAnywhere(CacheSimulation.sweep(CacheSimulation.ORIGINAL));
        assertFalse(violated.contains("ReturnsLatestPut"), "the original always returns the latest value written");
        assertFalse(violated.contains("ServesUnknownValue"), "the original never invents a value");
    }

    /** {@code ./gradlew cacheProblems -Dsim.seed=N}: replay one seed on both caches and print the full trace. */
    @Test
    void replaySeedFromProperty() {
        String property = System.getProperty("sim.seed");
        Assumptions.assumeTrue(property != null && !property.isBlank(), "set -Dsim.seed=<n> to replay one seed");
        long seed = Long.parseLong(property.trim());
        for (Map.Entry<String, CacheSimulation.CacheFactory> e : Map.of(
            "original (reviewed class)", CacheSimulation.ORIGINAL, "corrected sketch", CacheSimulation.CORRECTED).entrySet()) {
            CacheSimulation.Run run = CacheSimulation.simulate(seed, e.getValue());
            System.out.println("=== seed " + seed + " on " + e.getKey() + ": "
                + (run.violations.isEmpty() ? "no violations" : run.violatedInvariants()) + " ===");
            run.trace.forEach(System.out::println);
            run.violations.forEach(v -> System.out.println("  VIOLATION " + v));
        }
    }
}
