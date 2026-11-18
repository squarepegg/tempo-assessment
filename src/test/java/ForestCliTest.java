import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the "try your own input" tool ({@link ForestCli}, {@link ForestInput}): it draws the result for valid input
 * and names the broken rule for invalid input. Run with {@code ./gradlew test}.
 */
class ForestCliTest {

    private record Result(int exitCode, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ForestCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // The two commands shown in the README. The README must contain exactly this output.
    private static final String[] README_SUCCESS_ARGS = {"--forest", "1:0,2:1,3:2,4:1,5:0", "--reject", "2"};
    private static final String[] README_ERROR_ARGS = {"--forest", "1:0,2:2"};

    // ---- valid input ----

    @Test
    void drawsTheInputAndTheFilteredForestSideBySide() {
        Result result = run(README_SUCCESS_ARGS);

        assertEquals(0, result.exitCode());
        assertEquals("", result.err());
        assertEquals(String.join("\n",
            "input (5 nodes)                     after filter(reject 2)",
            "1                                   1",
            "- 2   <- rejected                   - 4",
            "- - 3   removed with an ancestor    5",
            "- 4",
            "5",
            "",
            "as --forest input: 1:0,4:1,5:0"), result.out().stripTrailing());
    }

    @Test
    void theTaskExampleGivesTheSameRowsAsTheGeneratedReadmeDiagram() {
        Result result = run("--forest", "1:0,2:1,3:2,4:3,5:1,6:0,7:1,8:0,9:1,10:1,11:2", "--reject-multiples-of", "3");

        assertEquals(0, result.exitCode());
        String[] cli = result.out().split("\n");
        String[] demo = ForestDemo.diagram().split("\n");
        for (int row = 1; row < demo.length; row++) { // row 0 is the header, whose label differs
            assertEquals(demo[row], cli[row], "row " + row);
        }
    }

    @Test
    void theResultIsPrintedInTheInputFormatSoItCanBeFedBack() throws Exception {
        Result result = run("--forest", "1:0,2:1,3:2,4:1,5:0,6:0", "--reject", "2,6");
        String line = result.out().lines().filter(l -> l.startsWith("as --forest input: ")).findFirst().orElseThrow();
        String pairs = line.substring("as --forest input: ".length());

        ForestInput again = ForestInput.fromPairs(pairs);

        assertEquals("[1:0, 4:1, 5:0]", again.toHierarchy().formatString());
    }

    @Test
    void rejectingNothingKeepsEverything() {
        Result result = run("--forest", "1:0,2:1");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("after filter(keep everything)"));
        assertTrue(result.out().contains("as --forest input: 1:0,2:1"));
    }

    @Test
    void anEmptyForestIsFine() {
        Result result = run("--forest", "", "--reject", "1");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("input (0 nodes)"));
        assertTrue(result.out().contains("as --forest input: (empty)"));
    }

    @Test
    void aDrawnForestFileIsReadBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("forest.txt");
        Files.writeString(file, ForestView.render(ForestDemo.input()) + "\n");

        Result result = run("--tree-file", file.toString(), "--reject-multiples-of", "3");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("as --forest input: 1:0,2:1,5:1,8:0,10:1,11:2"), result.out());
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        Result result = run("--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().startsWith("usage:"));
    }

    // ---- invalid forests: the error names the node and the rule ----

    private static void assertForestError(String expectedMessage, String... args) {
        Result result = run(args);
        assertEquals(1, result.exitCode(), String.join(" ", args));
        assertEquals("error: " + expectedMessage, result.err().stripTrailing(), String.join(" ", args));
        assertEquals("", result.out());
    }

    @Test
    void aDepthThatRisesByMoreThanOneIsRejected() {
        assertForestError("node at index 1 (id 2) has depth 2 but the node before it has depth 0; a depth can rise by at most 1 per node",
            README_ERROR_ARGS);
    }

    @Test
    void aFirstNodeThatIsNotARootIsRejected() {
        assertForestError("the first node (id 1) has depth 1; the first node must be a root, depth 0", "--forest", "1:1,2:2");
    }

    @Test
    void aNegativeDepthIsRejected() {
        assertForestError("node at index 1 (id 2) has depth -1; a depth cannot be negative", "--forest", "1:0,2:-1");
    }

    @Test
    void aDuplicateIdIsRejected() {
        assertForestError("id 1 appears at index 0 and at index 2; node ids must be unique", "--forest", "1:0,2:1,1:1");
    }

    @Test
    void anItemThatIsNotIdColonDepthIsRejected() {
        assertForestError("item 2 (\"x\") is not in the form id:depth, for example 3:2", "--forest", "1:0,x");
    }

    @Test
    void aNumberThatDoesNotFitAnIntIsRejected() {
        assertForestError("the id in item 1 (99999999999) does not fit in a 32-bit integer", "--forest", "99999999999:0");
    }

    @Test
    void aBadLineInADrawnForestIsRejectedWithItsLineNumber(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("forest.txt");
        Files.write(file, List.of("1", "- 2", "--3")); // "-3" alone would be a valid root with id -3

        assertForestError("line 3 (\"--3\") is not a drawn node: expected hyphens and spaces for depth, then an id, for example \"- - 3\"",
            "--tree-file", file.toString());
    }

    // ---- usage errors: the message, then the usage text ----

    private static void assertUsageError(String expectedMessage, String... args) {
        Result result = run(args);
        assertEquals(1, result.exitCode(), String.join(" ", args));
        assertTrue(result.err().startsWith("error: " + expectedMessage + "\n"), result.err());
        assertTrue(result.err().contains("usage:"), result.err());
    }

    @Test
    void usageErrorsExplainThemselves() {
        assertUsageError("give a forest with --forest or --tree-file", "--reject", "2");
        assertUsageError("give either --forest or --tree-file, not both", "--forest", "1:0", "--tree-file", "x");
        assertUsageError("unknown option \"--rejekt\"", "--forest", "1:0", "--rejekt", "2");
        assertUsageError("--reject needs a value", "--forest", "1:0", "--reject");
        assertUsageError("--reject expects comma-separated integers, got \"a,b\"", "--forest", "1:0", "--reject", "a,b");
        assertUsageError("--reject-multiples-of expects an integer of at least 1, got \"0\"", "--forest", "1:0", "--reject-multiples-of", "0");
        assertUsageError("cannot read /no/such/file: /no/such/file", "--tree-file", "/no/such/file");
    }

    // ---- the README shows exactly this output ----

    @Test
    void theReadmeShowsTheRealOutputOfItsExamples() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        String success = run(README_SUCCESS_ARGS).out().stripTrailing();
        String error = run(README_ERROR_ARGS).err().stripTrailing();

        assertTrue(readme.contains(success), "README.md no longer contains the real output of its success example. Expected:\n" + success);
        assertTrue(readme.contains(error), "README.md no longer contains the real output of its error example. Expected:\n" + error);
    }
}
