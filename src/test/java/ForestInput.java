import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A forest typed by a person, parsed and checked against the rules in the {@code Hierarchy} Javadoc.
 *
 * <p>{@code HierarchyFilter.filter} assumes its input obeys those rules and does not re-check them (see the README,
 * "Assumptions"). This class is where invalid input is caught for the command line tool, with an error that says which
 * node broke which rule.
 *
 * <p>Two formats, both produced by this project's own output:
 * <ul>
 *   <li>{@code id:depth} pairs, comma separated: {@code 1:0,2:1,3:2}</li>
 *   <li>a drawn forest, one node per line, one hyphen and a space per level: {@code - - 3}</li>
 * </ul>
 */
final class ForestInput {

    static final class InvalidForestException extends Exception {
        InvalidForestException(String message) {
            super(message);
        }
    }

    private static final Pattern PAIR = Pattern.compile("(-?\\d+):(-?\\d+)");
    private static final Pattern TREE_LINE = Pattern.compile("((?:- )*)(-?\\d+)");

    final int[] ids;
    final int[] depths;

    private ForestInput(int[] ids, int[] depths) {
        this.ids = ids;
        this.depths = depths;
    }

    Hierarchy toHierarchy() {
        return new ArrayBasedHierarchy(ids, depths);
    }

    /** {@code "1:0,2:1,3:2"}. An empty string is the empty forest. */
    static ForestInput fromPairs(String text) throws InvalidForestException {
        List<Integer> ids = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        if (!text.isBlank()) {
            String[] tokens = text.split(",", -1);
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].trim();
                Matcher m = PAIR.matcher(token);
                if (!m.matches()) {
                    throw new InvalidForestException("item " + (i + 1) + " (\"" + token + "\") is not in the form id:depth, for example 3:2");
                }
                ids.add(parseInt(m.group(1), "the id in item " + (i + 1)));
                depths.add(parseInt(m.group(2), "the depth in item " + (i + 1)));
            }
        }
        return validated(ids, depths);
    }

    /** One node per line: {@code "- - 3"} is id 3 at depth 2. Blank lines are ignored. */
    static ForestInput fromTreeLines(List<String> lines) throws InvalidForestException {
        List<Integer> ids = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).stripTrailing();
            if (line.isBlank()) continue;
            Matcher m = TREE_LINE.matcher(line);
            if (!m.matches()) {
                throw new InvalidForestException("line " + (i + 1) + " (\"" + line + "\") is not a drawn node: expected hyphens and spaces for depth, then an id, for example \"- - 3\"");
            }
            depths.add(m.group(1).length() / 2);
            ids.add(parseInt(m.group(2), "the id on line " + (i + 1)));
        }
        return validated(ids, depths);
    }

    private static int parseInt(String text, String what) throws InvalidForestException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new InvalidForestException(what + " (" + text + ") does not fit in a 32-bit integer");
        }
    }

    private static ForestInput validated(List<Integer> idList, List<Integer> depthList) throws InvalidForestException {
        int[] ids = idList.stream().mapToInt(Integer::intValue).toArray();
        int[] depths = depthList.stream().mapToInt(Integer::intValue).toArray();

        Map<Integer, Integer> firstIndexOfId = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (depths[i] < 0) {
                throw new InvalidForestException("node at index " + i + " (id " + ids[i] + ") has depth " + depths[i] + "; a depth cannot be negative");
            }
            if (i == 0 && depths[i] != 0) {
                throw new InvalidForestException("the first node (id " + ids[0] + ") has depth " + depths[0] + "; the first node must be a root, depth 0");
            }
            if (i > 0 && depths[i] > depths[i - 1] + 1) {
                throw new InvalidForestException("node at index " + i + " (id " + ids[i] + ") has depth " + depths[i]
                    + " but the node before it has depth " + depths[i - 1] + "; a depth can rise by at most 1 per node");
            }
            Integer earlier = firstIndexOfId.putIfAbsent(ids[i], i);
            if (earlier != null) {
                throw new InvalidForestException("id " + ids[i] + " appears at index " + earlier + " and at index " + i + "; node ids must be unique");
            }
        }
        return new ForestInput(ids, depths);
    }
}
