import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

// The task:
// 1. Read and understand the Hierarchy data structure described in this file.
// 2. Implement filter() method.
// 3. Implement more test cases.   (see src/test/java/FilterTest.java)
//
// The task should take 30-90 minutes.
//
// When assessing the submission, we will pay attention to:
// - correctness, efficiency, and clarity of the code;
// - the test cases.

/**
 * A {@code Hierarchy} stores an arbitrary <i>forest</i> (an ordered collection of ordered trees)
 * as an array of node IDs in the order of DFS traversal, combined with a parallel array of node depths.
 *
 * <p>Parent-child relationships are identified by the position in the array and the associated depth.
 * Each tree root has depth 0, its children have depth 1 and follow it in the array, their children have depth 2 and follow them, etc.
 *
 * <p>Example:
 * <pre>
 * nodeIds: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
 * depths:  0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2
 * </pre>
 *
 * <p>the forest can be visualized as follows:
 * <pre>
 * 1
 * - 2
 * - - 3
 * - - - 4
 * - 5
 * 6
 * - 7
 * 8
 * - 9
 * - 10
 * - - 11
 * </pre>
 * 1 is a parent of 2 and 5, 2 is a parent of 3, etc. Note that depth is equal to the number of hyphens for each node.
 *
 * <p>Invariants on the depths array:
 * <ul>
 *   <li>Depth of the first element is 0.</li>
 *   <li>If the depth of a node is {@code D}, the depth of the next node in the array can be:
 *     <ul>
 *       <li>{@code D + 1} if the next node is a child of this node;</li>
 *       <li>{@code D} if the next node is a sibling of this node;</li>
 *       <li>{@code d < D} - in this case the next node is not related to this node.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
interface Hierarchy {
    /** The number of nodes in the hierarchy. */
    int size();

    /**
     * Returns the unique ID of the node identified by the hierarchy index. The depth for this node will be {@code depth(index)}.
     * @param index must be non-negative and less than {@link #size()}
     */
    int nodeId(int index);

    /**
     * Returns the depth of the node identified by the hierarchy index. The unique ID for this node will be {@code nodeId(index)}.
     * @param index must be non-negative and less than {@link #size()}
     */
    int depth(int index);

    default String formatString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nodeId(i)).append(":").append(depth(i));
        }
        sb.append("]");
        return sb.toString();
    }
}

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 */
class HierarchyFilter {

    /** Sentinel for "not inside a rejected subtree". No real depth is greater than it. */
    private static final int NOT_SKIPPING = Integer.MAX_VALUE;

    /**
     * Returns a new hierarchy containing exactly the nodes whose ID passes {@code nodeIdPredicate} and whose
     * ancestors all pass it too. Relative order and depths of the surviving nodes are unchanged.
     *
     * <p>Why depths need no adjusting: a node survives only if every ancestor survives, so a surviving node
     * keeps its entire ancestor chain and therefore its depth.
     *
     * <p>Algorithm: one left-to-right pass. When a node is rejected, its whole subtree is exactly the run of
     * following nodes that are deeper than it, because in DFS order a subtree is contiguous and ends at the
     * first node with depth &lt;= the rejected node's depth. We remember the rejected node's depth and skip
     * until that run ends. O(n) time, O(n) space for the result, O(1) extra state, no recursion (so depth
     * is not limited by the stack).
     *
     * <p>The predicate is called at most once per node, and never for a node inside a rejected subtree.
     * It should be side-effect free and fast; callers must not rely on being called for skipped nodes.
     *
     * <p>Assumes {@code hierarchy} satisfies the invariants documented on {@link Hierarchy}; they are not
     * re-validated here (that would cost an extra pass), so invalid input gives unspecified output.
     * The input is not modified and the result does not share arrays with it.
     *
     * @throws NullPointerException if either argument is null
     */
    public static Hierarchy filter(Hierarchy hierarchy, IntPredicate nodeIdPredicate) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(nodeIdPredicate, "nodeIdPredicate");

        int size = hierarchy.size();
        int[] ids = new int[size];
        int[] depths = new int[size];
        int kept = 0;
        int rejectedDepth = NOT_SKIPPING;

        for (int i = 0; i < size; i++) {
            int depth = hierarchy.depth(i);
            if (depth > rejectedDepth) {
                continue; // descendant of a rejected node
            }
            rejectedDepth = NOT_SKIPPING; // the rejected subtree (if any) ended before this node

            int id = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(id)) {
                ids[kept] = id;
                depths[kept] = depth;
                kept++;
            } else {
                rejectedDepth = depth;
            }
        }

        if (kept < size) {
            ids = Arrays.copyOf(ids, kept);
            depths = Arrays.copyOf(depths, kept);
        }
        return new ArrayBasedHierarchy(ids, depths);
    }
}

class ArrayBasedHierarchy implements Hierarchy {
    private final int[] nodeIds;
    private final int[] depths;

    public ArrayBasedHierarchy(int[] nodeIds, int[] depths) {
        this.nodeIds = nodeIds;
        this.depths = depths;
    }

    @Override
    public int size() {
        return depths.length;
    }

    @Override
    public int nodeId(int index) {
        return nodeIds[index];
    }

    @Override
    public int depth(int index) {
        return depths[index];
    }
}
