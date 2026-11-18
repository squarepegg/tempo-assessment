import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** Draws forests the way the {@code Hierarchy} Javadoc does: one node per line, one hyphen per level of depth. */
final class ForestView {

    private static final int GAP = 4;

    private ForestView() {
    }

    static String render(Hierarchy hierarchy) {
        if (hierarchy.size() == 0) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hierarchy.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("- ".repeat(Math.max(0, hierarchy.depth(i)))).append(hierarchy.nodeId(i));
        }
        return sb.toString();
    }

    /**
     * The input forest and the filtered forest side by side. Input nodes that were dropped say why: {@code <- rejected}
     * when the predicate rejected the node itself, {@code removed with an ancestor} when an ancestor was rejected.
     *
     * @param label what is shown above the right-hand column, after "after "
     */
    static String sideBySide(Hierarchy input, IntPredicate keep, String label) {
        Hierarchy output = HierarchyFilter.filter(input, keep);

        Set<Integer> kept = new HashSet<>();
        for (int i = 0; i < output.size(); i++) kept.add(output.nodeId(i));

        String header = "input (" + input.size() + " nodes)";
        String[] left = new String[input.size()];
        int width = header.length();
        for (int i = 0; i < input.size(); i++) {
            int id = input.nodeId(i);
            String line = "- ".repeat(Math.max(0, input.depth(i))) + id;
            if (!kept.contains(id)) {
                line += keep.test(id) ? "   removed with an ancestor" : "   <- rejected";
            }
            left[i] = line;
            width = Math.max(width, line.length());
        }
        width += GAP;

        String[] right = render(output).split("\n");
        StringBuilder sb = new StringBuilder(row(header, "after " + label, width));
        for (int i = 0; i < left.length; i++) {
            sb.append('\n').append(row(left[i], i < right.length ? right[i] : "", width));
        }
        return sb.toString();
    }

    private static String row(String left, String right, int width) {
        return String.format("%-" + width + "s%s", left, right).stripTrailing();
    }
}
