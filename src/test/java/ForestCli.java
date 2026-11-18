import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/**
 * Filter your own forest from the command line and see the result drawn, or an error naming the rule your input broke.
 *
 * <pre>
 * ./gradlew forest -q --args="--forest 1:0,2:1,3:2,4:1,5:0 --reject 2"
 * </pre>
 *
 * Input is checked against the rules in the {@code Hierarchy} Javadoc before {@code filter} runs (see
 * {@link ForestInput}); {@code filter} itself assumes valid input. Exit code 0 on success, 1 on invalid input or usage.
 */
public final class ForestCli {

    static final String USAGE = String.join("\n",
        "usage: ./gradlew forest -q --args=\"<forest> <what to reject>\"",
        "",
        "forest (one of):",
        "  --forest <id:depth,...>     e.g. 1:0,2:1,3:2,4:1,5:0   (ids unique; first depth 0; depth rises by at most 1)",
        "  --tree-file <path>          one node per line, a hyphen and a space per level: \"- - 3\"",
        "",
        "what to reject (any combination):",
        "  --reject <id,id,...>        reject these ids",
        "  --reject-multiples-of <n>   reject ids divisible by n (n >= 1)",
        "",
        "A node survives only if it and all its ancestors are kept. The result is printed drawn as trees and as id:depth");

    private ForestCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return execute(args, out);
        } catch (ForestInput.InvalidForestException | UsageException e) {
            err.println("error: " + e.getMessage());
            if (e instanceof UsageException) err.println("\n" + USAGE);
            return 1;
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static int execute(String[] args, PrintStream out) throws ForestInput.InvalidForestException {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            out.println(USAGE);
            return 0;
        }

        String pairs = null;
        String treeFile = null;
        Set<Integer> rejectedIds = new TreeSet<>();
        int rejectMultiplesOf = 0;

        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--forest", "--tree-file", "--reject", "--reject-multiples-of" -> {
                    if (i + 1 >= args.length) throw new UsageException(option + " needs a value");
                    String value = args[++i];
                    switch (option) {
                        case "--forest" -> pairs = value;
                        case "--tree-file" -> treeFile = value;
                        case "--reject" -> rejectedIds.addAll(parseIds(value));
                        default -> rejectMultiplesOf = parsePositive(value);
                    }
                }
                default -> throw new UsageException("unknown option \"" + option + "\"");
            }
        }

        if (pairs == null && treeFile == null) throw new UsageException("give a forest with --forest or --tree-file");
        if (pairs != null && treeFile != null) throw new UsageException("give either --forest or --tree-file, not both");

        ForestInput forest;
        if (pairs != null) {
            forest = ForestInput.fromPairs(pairs);
        } else {
            forest = ForestInput.fromTreeLines(readLines(treeFile));
        }

        final int multiple = rejectMultiplesOf;
        final Set<Integer> rejected = rejectedIds;
        IntPredicate keep = id -> !rejected.contains(id) && !(multiple > 0 && Math.floorMod(id, multiple) == 0);

        Hierarchy input = forest.toHierarchy();
        Hierarchy output = HierarchyFilter.filter(input, keep);

        out.println(ForestView.sideBySide(input, keep, "filter(" + describe(rejected, multiple) + ")"));
        out.println();
        out.println("as --forest input: " + asPairs(output));
        return 0;
    }

    private static String describe(Set<Integer> rejectedIds, int multiple) {
        StringBuilder sb = new StringBuilder();
        if (!rejectedIds.isEmpty()) {
            sb.append("reject ").append(rejectedIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (multiple > 0) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("reject multiples of ").append(multiple);
        }
        return sb.length() == 0 ? "keep everything" : sb.toString();
    }

    private static String asPairs(Hierarchy hierarchy) {
        if (hierarchy.size() == 0) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hierarchy.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(hierarchy.nodeId(i)).append(':').append(hierarchy.depth(i));
        }
        return sb.toString();
    }

    private static List<String> readLines(String path) {
        try {
            return Files.readAllLines(Path.of(path));
        } catch (IOException e) {
            throw new UsageException("cannot read " + path + ": " + e.getMessage());
        }
    }

    private static List<Integer> parseIds(String text) {
        try {
            return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
        } catch (NumberFormatException e) {
            throw new UsageException("--reject expects comma-separated integers, got \"" + text + "\"");
        }
    }

    private static int parsePositive(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value >= 1) return value;
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new UsageException("--reject-multiples-of expects an integer of at least 1, got \"" + text + "\"");
    }
}
