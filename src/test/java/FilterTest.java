import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyFilter#filter} (Task 1). Run with {@code ./gradlew test}, or a single test with
 * {@code ./gradlew test --tests 'FilterTest.<method name>'}. Every test here is expected to pass.
 *
 * <p>Sections, in order: the provided example; degenerate inputs; the defining rule (a node needs all ancestors to
 * pass); where a skipped subtree can end; shapes and extreme values; the contract (predicate call count, no mutation,
 * no aliasing, null arguments); a randomized comparison against an independent reference implementation; and
 * metamorphic properties (relations between two runs, with no reference implementation).
 *
 * <p>To see these tests fail on deliberately broken code, run {@code ./scripts/mutation-check.sh}. Each of its nine
 * mutants is caught by at least one test here; the README table says which.
 */
class FilterTest {

    // Shared fixture from the task statement:
    //  1
    //  - 2
    //  - - 3
    //  - - - 4
    //  - 5
    //  6
    //  - 7
    //  8
    //  - 9
    //  - 10
    //  - - 11
    private static Hierarchy example() {
        return h(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
                 new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2});
    }

    private static Hierarchy h(int[] ids, int[] depths) {
        return new ArrayBasedHierarchy(ids, depths);
    }

    private static void assertHierarchy(int[] expectedIds, int[] expectedDepths, Hierarchy actual) {
        assertSameForest(h(expectedIds, expectedDepths), actual, "");
    }

    /** Asserts two forests are equal. On a difference the message shows both as lists and as drawn trees. */
    private static void assertSameForest(Hierarchy expected, Hierarchy actual, String message) {
        if (expected.formatString().equals(actual.formatString())) return;
        fail((message.isEmpty() ? "" : message + "\n")
            + "expected " + expected.formatString() + "\n  actual " + actual.formatString()
            + "\n\nexpected forest:\n" + ForestView.render(expected)
            + "\n\nactual forest:\n" + ForestView.render(actual));
    }

    // ---- the provided test, unchanged ----

    @Test
    void testFilter() {
        Hierarchy unfiltered = new ArrayBasedHierarchy(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2}
        );
        Hierarchy filteredActual = HierarchyFilter.filter(unfiltered, nodeId -> nodeId % 3 != 0);
        Hierarchy filteredExpected = new ArrayBasedHierarchy(
            new int[]{1, 2, 5, 8, 10, 11},
            new int[]{0, 1, 1, 0, 1, 2}
        );
        assertEquals(filteredExpected.formatString(), filteredActual.formatString());
    }

    // ---- degenerate inputs ----

    @Test
    void emptyHierarchyGivesEmptyHierarchy() {
        Hierarchy result = HierarchyFilter.filter(h(new int[0], new int[0]), id -> true);
        assertEquals(0, result.size());
    }

    @Test
    void singleNodePassing() {
        assertHierarchy(new int[]{7}, new int[]{0}, HierarchyFilter.filter(h(new int[]{7}, new int[]{0}), id -> true));
    }

    @Test
    void singleNodeRejected() {
        assertEquals(0, HierarchyFilter.filter(h(new int[]{7}, new int[]{0}), id -> false).size());
    }

    @Test
    void everythingPassesGivesSameContent() {
        Hierarchy result = HierarchyFilter.filter(example(), id -> true);
        assertSameForest(example(), result, "everything passes");
    }

    @Test
    void nothingPassesGivesEmpty() {
        assertEquals(0, HierarchyFilter.filter(example(), id -> false).size());
    }

    // ---- the defining rule: a node needs ALL ancestors to pass ----

    @Test
    void rejectedNodeRemovesItsWholeSubtreeEvenIfDescendantsPass() {
        // reject only 2: its descendants 3 and 4 pass the predicate but must go
        Hierarchy result = HierarchyFilter.filter(example(), id -> id != 2);
        assertHierarchy(new int[]{1, 5, 6, 7, 8, 9, 10, 11}, new int[]{0, 1, 0, 1, 0, 1, 1, 2}, result);
    }

    @Test
    void rejectedRootRemovesWholeTreeAndLeavesOtherTreesIntact() {
        Hierarchy result = HierarchyFilter.filter(example(), id -> id != 1);
        assertHierarchy(new int[]{6, 7, 8, 9, 10, 11}, new int[]{0, 1, 0, 1, 1, 2}, result);
    }

    @Test
    void rejectedLeafOnlyRemovesItself() {
        Hierarchy result = HierarchyFilter.filter(example(), id -> id != 4);
        assertHierarchy(new int[]{1, 2, 3, 5, 6, 7, 8, 9, 10, 11}, new int[]{0, 1, 2, 1, 0, 1, 0, 1, 1, 2}, result);
    }

    @Test
    void onlyDeepDescendantPassesButAncestorsFail() {
        // 11 passes, but its parent 10 and root 8 do not
        Hierarchy result = HierarchyFilter.filter(example(), id -> id == 11 || id == 1 || id == 6);
        assertHierarchy(new int[]{1, 6}, new int[]{0, 0}, result);
    }

    @Test
    void parentFailsChildPassesIsStillRemoved() {
        Hierarchy in = h(new int[]{1, 2}, new int[]{0, 1});
        assertEquals(0, HierarchyFilter.filter(in, id -> id == 2).size());
    }

    // ---- boundaries of a skipped subtree ----

    @Test
    void skippingEndsAtASiblingOfTheRejectedNode() {
        // 2 rejected; next node after its subtree (5) is a sibling at the same depth and must be evaluated
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 3, 1});
        assertHierarchy(new int[]{1, 5}, new int[]{0, 1}, HierarchyFilter.filter(in, id -> id != 2));
    }

    @Test
    void skippingEndsAtAShallowerNode() {
        // 3 (depth 2) rejected; next node 5 is at depth 1, shallower than the rejected one
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 3, 1});
        assertHierarchy(new int[]{1, 2, 5}, new int[]{0, 1, 1}, HierarchyFilter.filter(in, id -> id != 3));
    }

    @Test
    void skippingEndsAtANewTree() {
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 0});
        assertHierarchy(new int[]{4}, new int[]{0}, HierarchyFilter.filter(in, id -> id != 1));
    }

    @Test
    void consecutiveRejectedSiblingsAreAllRemoved() {
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 1, 2, 1});
        // 2 and 4 rejected, with children 3 and 5; 6 survives
        assertHierarchy(new int[]{1, 6}, new int[]{0, 1}, HierarchyFilter.filter(in, id -> id != 2 && id != 4));
    }

    @Test
    void rejectedNodeNestedInsideAnotherRejectedSubtreeDoesNotConfuseTheBoundary() {
        // 2 and its child 3 would both be rejected; the skip must end after 2's subtree, not after 3's
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 3, 2, 1});
        assertHierarchy(new int[]{1, 6}, new int[]{0, 1}, HierarchyFilter.filter(in, id -> id != 2 && id != 3));
    }

    @Test
    void depthDropsByMoreThanOneLevelAreHandled() {
        // allowed by the invariants: 3 -> 0 closes several levels at once
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 3, 0});
        assertHierarchy(new int[]{1, 2, 5}, new int[]{0, 1, 0}, HierarchyFilter.filter(in, id -> id != 3));
    }

    // ---- shapes ----

    @Test
    void forestOfOnlyRoots() {
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 0, 0, 0});
        assertHierarchy(new int[]{1, 3}, new int[]{0, 0}, HierarchyFilter.filter(in, id -> id % 2 == 1));
    }

    @Test
    void negativeAndExtremeIdsAreJustValues() {
        Hierarchy in = h(new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, new int[]{0, 1, 1, 0});
        assertHierarchy(new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, new int[]{0, 1, 0},
            HierarchyFilter.filter(in, id -> id != -1));
    }

    @Test
    void veryDeepChainDoesNotOverflowTheStack() {
        int n = 500_000;
        int[] ids = new int[n];
        int[] depths = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            depths[i] = i;
        }
        Hierarchy in = h(ids, depths);
        // reject the node halfway down: everything below it goes too
        Hierarchy result = HierarchyFilter.filter(in, id -> id != n / 2);
        assertEquals(n / 2, result.size());
        assertEquals(n / 2 - 1, result.depth(result.size() - 1));
    }

    // ---- contract ----

    @Test
    void predicateIsCalledAtMostOncePerNodeAndNeverForNodesInsideARejectedSubtree() {
        List<Integer> seen = new ArrayList<>();
        HierarchyFilter.filter(example(), id -> {
            seen.add(id);
            return id != 2; // 3 and 4 are inside the rejected subtree
        });
        assertEquals(List.of(1, 2, 5, 6, 7, 8, 9, 10, 11), seen);
    }

    @Test
    void inputIsNotModified() {
        Hierarchy in = example();
        String before = in.formatString();
        HierarchyFilter.filter(in, id -> id % 2 == 0);
        assertEquals(before, in.formatString());
    }

    @Test
    void resultIsIndependentOfTheInputArrays() {
        int[] ids = {1, 2, 3};
        int[] depths = {0, 1, 1};
        Hierarchy result = HierarchyFilter.filter(h(ids, depths), id -> true);
        ids[0] = 99;
        depths[1] = 0;
        assertHierarchy(new int[]{1, 2, 3}, new int[]{0, 1, 1}, result);
    }

    @Test
    void worksOnAnyHierarchyImplementationNotJustTheArrayBackedOne() {
        // a lazily computed chain 0 -> 1 -> 2 -> ... -> 9 that never materializes arrays
        Hierarchy chain = new Hierarchy() {
            public int size() { return 10; }
            public int nodeId(int index) { return index; }
            public int depth(int index) { return index; }
        };
        Hierarchy result = HierarchyFilter.filter(chain, id -> id < 4);
        assertHierarchy(new int[]{0, 1, 2, 3}, new int[]{0, 1, 2, 3}, result);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(null, id -> true));
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(example(), null));
        // fail fast: a null predicate is a bug even when the hierarchy is empty and it would never be called
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(h(new int[0], new int[0]), null));
    }

    // ---- randomized comparison against an independent implementation ----

    @Test
    void matchesReferenceImplementationOnRandomForests() {
        for (long seed = 0; seed < 2_000; seed++) {
            Random random = new Random(seed);
            Hierarchy forest = randomForest(random, random.nextInt(60));
            int mod = 2 + random.nextInt(5);
            int salt = random.nextInt(mod);
            IntPredicate predicate = id -> Math.floorMod(id, mod) != salt;

            Hierarchy actual = HierarchyFilter.filter(forest, predicate);
            Hierarchy expected = reference(forest, predicate);

            assertSameForest(expected, actual, "seed " + seed);
            assertValidForest(actual, "seed " + seed);
        }
    }

    /** Parent-pointer formulation, deliberately different from the production skip-depth algorithm. */
    private static Hierarchy reference(Hierarchy in, IntPredicate predicate) {
        int n = in.size();
        boolean[] kept = new boolean[n];
        int[] parentAtDepth = new int[n + 1];
        List<Integer> ids = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int d = in.depth(i);
            parentAtDepth[d] = i;
            boolean parentKept = d == 0 || kept[parentAtDepth[d - 1]];
            kept[i] = parentKept && predicate.test(in.nodeId(i));
            if (kept[i]) {
                ids.add(in.nodeId(i));
                depths.add(d);
            }
        }
        return h(ids.stream().mapToInt(Integer::intValue).toArray(),
                 depths.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Random valid forest with unique ids, built from the documented invariants. */
    private static Hierarchy randomForest(Random random, int size) {
        int[] ids = new int[size];
        int[] depths = new int[size];
        for (int i = 0; i < size; i++) {
            ids[i] = i + 1;
            // allowed: previous + 1 (child), or anything in 0..previous (sibling or shallower)
            depths[i] = i == 0 ? 0 : random.nextInt(depths[i - 1] + 2);
        }
        return h(ids, depths);
    }

    private static void assertValidForest(Hierarchy hierarchy, String message) {
        if (hierarchy.size() == 0) return;
        assertEquals(0, hierarchy.depth(0), message + ": first depth must be 0");
        for (int i = 1; i < hierarchy.size(); i++) {
            assertTrue(hierarchy.depth(i) <= hierarchy.depth(i - 1) + 1,
                message + ": depth may rise by at most 1 at index " + i);
            assertTrue(hierarchy.depth(i) >= 0, message + ": negative depth at index " + i);
        }
    }

    // ---- metamorphic properties ----
    // Relationships that must hold between two runs of filter. They need no reference implementation. Limits, measured
    // with scripts/mutation-check.sh on each property alone: composition, loosening and order/depth preservation are
    // consistency checks that a filter judging each node on its own would also satisfy, so they do not pin the ancestor
    // rule; the subtree-removal property below does. The existing tests already kill every mutant; these add
    // independent, spec-level checks, not extra kill power.

    /** A random predicate that rejects roughly one id in {@code modulus}. */
    private static IntPredicate randomPredicate(Random random) {
        int modulus = 2 + random.nextInt(5);
        int remainder = random.nextInt(modulus);
        return id -> Math.floorMod(id, modulus) != remainder;
    }

    private static List<Integer> idsOf(Hierarchy hierarchy) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < hierarchy.size(); i++) ids.add(hierarchy.nodeId(i));
        return ids;
    }

    /** Filtering with p and then with q is the same as filtering once with "p and q". */
    @Test
    void filteringWithPThenQEqualsFilteringOnceWithPAndQ() {
        for (long seed = 0; seed < 1_000; seed++) {
            Random random = new Random(seed);
            Hierarchy forest = randomForest(random, random.nextInt(60));
            IntPredicate p = randomPredicate(random);
            IntPredicate q = randomPredicate(random);

            Hierarchy twoSteps = HierarchyFilter.filter(HierarchyFilter.filter(forest, p), q);
            Hierarchy oneStep = HierarchyFilter.filter(forest, p.and(q));

            assertSameForest(oneStep, twoSteps, "seed " + seed);
        }
    }

    /**
     * Rejecting one more node removes that node and its descendants from the output, and nothing else. This is the
     * defining rule ("a node needs all its ancestors to pass") stated as a relation between two runs. The composition
     * and loosening properties above do NOT pin it: a filter that judged every node on its own would satisfy them too.
     */
    @Test
    void rejectingOneMoreNodeRemovesExactlyItsSubtreeFromTheOutput() {
        for (long seed = 0; seed < 1_000; seed++) {
            Random random = new Random(seed);
            Hierarchy forest = randomForest(random, 1 + random.nextInt(60));
            IntPredicate p = randomPredicate(random);
            Hierarchy base = HierarchyFilter.filter(forest, p);
            if (base.size() == 0) continue;

            int position = random.nextInt(base.size());
            int rejectedId = base.nodeId(position);
            Hierarchy actual = HierarchyFilter.filter(forest, p.and(id -> id != rejectedId));

            // expected: the base output without the node at `position` and the run of deeper nodes right after it
            int subtreeEnd = position + 1;
            while (subtreeEnd < base.size() && base.depth(subtreeEnd) > base.depth(position)) subtreeEnd++;
            List<Integer> expectedIds = new ArrayList<>();
            List<Integer> expectedDepths = new ArrayList<>();
            for (int i = 0; i < base.size(); i++) {
                if (i >= position && i < subtreeEnd) continue;
                expectedIds.add(base.nodeId(i));
                expectedDepths.add(base.depth(i));
            }
            Hierarchy expected = h(expectedIds.stream().mapToInt(Integer::intValue).toArray(),
                                   expectedDepths.stream().mapToInt(Integer::intValue).toArray());

            assertSameForest(expected, actual, "seed " + seed + ", rejected id " + rejectedId);
        }
    }

    // ---- the renderer used in failure messages ----

    /** The renderer draws one hyphen per level of depth, exactly as the {@code Hierarchy} Javadoc does. */
    @Test
    void renderDrawsTheForestAsTheJavadocDoes() {
        String expected = String.join("\n", "1", "- 2", "- - 3", "- - - 4", "- 5", "6", "- 7", "8", "- 9", "- 10", "- - 11");
        assertEquals(expected, ForestView.render(example()));
        assertEquals("(empty)", ForestView.render(h(new int[0], new int[0])));
    }

    /** A difference fails the assertion and shows both forests drawn; identical forests pass. */
    @Test
    void aMismatchFailsAndShowsBothForestsDrawn() {
        assertSameForest(example(), example(), "identical forests pass");

        Hierarchy different = h(new int[]{1, 2}, new int[]{0, 1});
        AssertionError error = assertThrows(AssertionError.class, () -> assertSameForest(example(), different, "context"));
        assertTrue(error.getMessage().contains("context"));
        assertTrue(error.getMessage().contains("expected forest:\n1\n- 2\n- - 3"), error.getMessage());
        assertTrue(error.getMessage().contains("actual forest:\n1\n- 2"), error.getMessage());
    }

    /**
     * The diagram in README.md is exactly what the code prints ({@code ./gradlew showExample -q}), so it cannot go stale.
     * Fails with the regeneration command if the README and the real output differ.
     */
    @Test
    void theReadmeDiagramIsTheRealOutput() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains(ForestDemo.diagram()),
            "README.md no longer contains the generated diagram. Regenerate it with ./gradlew showExample -q and paste it in. Expected:\n"
            + ForestDemo.diagram());
    }

    /** A looser predicate ("p or r") keeps every node a stricter one ("p") keeps. */
    @Test
    void aLooserPredicateKeepsEverythingAStricterOneKeeps() {
        for (long seed = 0; seed < 1_000; seed++) {
            Random random = new Random(seed);
            Hierarchy forest = randomForest(random, random.nextInt(60));
            IntPredicate strict = randomPredicate(random);
            IntPredicate loose = strict.or(randomPredicate(random));

            List<Integer> keptByStrict = idsOf(HierarchyFilter.filter(forest, strict));
            List<Integer> keptByLoose = idsOf(HierarchyFilter.filter(forest, loose));

            assertTrue(keptByLoose.containsAll(keptByStrict), "seed " + seed);
        }
    }

    /** The output is the input with some nodes removed: same relative order, and each surviving node keeps its depth. */
    @Test
    void theOutputIsTheInputWithNodesRemovedInTheSameOrderWithTheSameDepths() {
        for (long seed = 0; seed < 1_000; seed++) {
            Random random = new Random(seed);
            Hierarchy forest = randomForest(random, random.nextInt(60)); // ids are 1..n in input order
            Hierarchy result = HierarchyFilter.filter(forest, randomPredicate(random));

            int previousInputIndex = -1;
            for (int i = 0; i < result.size(); i++) {
                int inputIndex = result.nodeId(i) - 1;
                assertTrue(inputIndex > previousInputIndex, "order changed, seed " + seed);
                assertEquals(forest.depth(inputIndex), result.depth(i), "depth changed, seed " + seed);
                previousInputIndex = inputIndex;
            }
        }
    }
}
