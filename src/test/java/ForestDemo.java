import java.util.function.IntPredicate;

/**
 * The README's worked example, generated from the real code: the task's 11-node forest before and after
 * {@code filter(id % 3 != 0)}, drawn as trees.
 *
 * <p>Run {@code ./gradlew showExample -q} to print it. CI prints it in the job summary, and {@code FilterTest} checks
 * that the diagram in {@code README.md} is exactly this output, so the README cannot drift from the code. To try your
 * own forest, see {@link ForestCli} ({@code ./gradlew forest}).
 */
public final class ForestDemo {

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
        return ForestView.sideBySide(input(), PREDICATE, "filter(id % 3 != 0)");
    }

    public static void main(String[] args) {
        System.out.println(diagram());
    }
}
