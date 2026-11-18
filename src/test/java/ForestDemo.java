import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The README's worked example, generated from the real code: the task's 11-node forest before and after
 * {@code filter(id % 3 != 0)}, drawn as trees.
 *
 * <p>Run {@code ./gradlew showExample -q} to print it. CI prints it in the job summary, and {@code FilterTest} checks
 * that the diagram in {@code README.md} is exactly this output, so the README cannot drift from the code.
 */
public final class ForestDemo {

    private static final int LEFT_COLUMN_WIDTH = 38;
    private static final IntPredicate PREDICATE = id -> id % 3 != 0;

    private ForestDemo() {
    }

    static Hierarchy input() {
        return new ArrayBasedHierarchy(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2});
    }

    /** The input and the filtered forest side by side. Nodes that were dropped say why. */
    static String diagram() {
        Hierarchy input = input();
        Hierarchy output = HierarchyFilter.filter(input, PREDICATE);

        Set<Integer> kept = new HashSet<>();
        for (int i = 0; i < output.size(); i++) kept.add(output.nodeId(i));

        String[] left = new String[input.size()];
        for (int i = 0; i < input.size(); i++) {
            int id = input.nodeId(i);
            String line = "- ".repeat(input.depth(i)) + id;
            if (!kept.contains(id)) {
                line += PREDICATE.test(id) ? "   removed with an ancestor" : "   <- rejected";
            }
            left[i] = line;
        }
        String[] right = ForestView.render(output).split("\n");

        StringBuilder sb = new StringBuilder();
        sb.append(row("input (" + input.size() + " nodes)", "after filter(id % 3 != 0)"));
        for (int i = 0; i < left.length; i++) {
            sb.append('\n').append(row(left[i], i < right.length ? right[i] : ""));
        }
        return sb.toString();
    }

    private static String row(String left, String right) {
        return String.format("%-" + LEFT_COLUMN_WIDTH + "s%s", left, right).stripTrailing();
    }

    public static void main(String[] args) {
        System.out.println(diagram());
    }
}
