/** Draws a forest the way the {@code Hierarchy} Javadoc does: one node per line, one hyphen per level of depth. */
final class ForestView {

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
}
