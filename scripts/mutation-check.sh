#!/usr/bin/env bash
#
# Mutation check: proves the tests can actually fail.
#
# For each mutant, this copies the project to a temp dir, applies ONE deliberate bug to
# HierarchyFilter.filter(), runs the test suite there, and reports which tests failed.
# A mutant is "killed" if at least one test fails. A "survived" mutant means the tests
# have a blind spot, and the script exits non-zero.
#
# Your working tree is never modified.
#
# Usage: ./scripts/mutation-check.sh        (any JDK 17+ on JAVA_HOME / PATH)

set -uo pipefail
cd "$(dirname "$0")/.."

SRC=src/main/java/Hierarchy.java
WORK_ROOT=$(mktemp -d)
trap 'rm -rf "$WORK_ROOT"' EXIT

killed=0
survived=0
invalid=0

prepare_copy() {
    local dir=$1
    mkdir -p "$dir"
    cp -R . "$dir"
    rm -rf "$dir/build" "$dir/.gradle" "$dir/.git"
}

# Optional first argument: a Gradle test filter, e.g. ./scripts/mutation-check.sh 'FilterTest.filteringTwice*'
# Runs only those tests against every mutant, to see what a subset of the suite can catch on its own.
TEST_FILTER="${1:-}"

run_tests() {
    # prints Gradle output; exit status is Gradle's
    if [ -n "$TEST_FILTER" ]; then
        (cd "$1" && ./gradlew test --tests "$TEST_FILTER" --console=plain --rerun-tasks 2>&1)
    else
        (cd "$1" && ./gradlew test --console=plain --rerun-tasks 2>&1)
    fi
}

# mutant <name> <what is broken> <sed expression>
mutant() {
    local name=$1 description=$2 expr=$3
    local dir="$WORK_ROOT/$name"
    prepare_copy "$dir"

    sed "$expr" "$SRC" > "$dir/$SRC"
    if cmp -s "$SRC" "$dir/$SRC"; then
        echo "  INVALID  $name: the sed expression did not change the source (update this script)"
        invalid=$((invalid + 1))
        return
    fi

    local output status
    output=$(run_tests "$dir")
    status=$?

    if echo "$output" | grep -q "compileJava FAILED"; then
        echo "  INVALID  $name: mutant does not compile (update this script)"
        invalid=$((invalid + 1))
        return
    fi

    # A mutant is killed if the suite fails. A filtered run reports failures but exits 0, so look at the test lines too.
    if [ $status -ne 0 ] || echo "$output" | grep -qE "^FilterTest > .* FAILED"; then
        local failing
        failing=$(echo "$output" | grep -E "^FilterTest > .* FAILED" | sed -E 's/^FilterTest > (.*) FAILED/\1/')
        local count
        count=$(echo "$failing" | grep -c .)
        echo "  KILLED   $name ($count failing): $description"
        echo "$failing" | head -4 | sed 's/^/             - /'
        [ "$count" -gt 4 ] && echo "             - ... and $((count - 4)) more"
        killed=$((killed + 1))
    else
        echo "  SURVIVED $name: $description"
        echo "             The tests did not notice this bug."
        survived=$((survived + 1))
    fi
}

echo "Baseline: unmodified code must pass"
baseline_dir="$WORK_ROOT/baseline"
prepare_copy "$baseline_dir"
if ! run_tests "$baseline_dir" > "$WORK_ROOT/baseline.log"; then
    cat "$WORK_ROOT/baseline.log"
    echo "Baseline failed, aborting."
    exit 2
fi
echo "  OK"
echo
echo "Mutants (each must make at least one test fail):"

mutant boundary-inclusive \
    "skip boundary '>' becomes '>=': a sibling of a rejected node is wrongly dropped" \
    's/if (depth > rejectedDepth) {/if (depth >= rejectedDepth) {/'

mutant only-direct-children \
    "only the rejected node's direct children are dropped, deeper descendants are kept" \
    's/if (depth > rejectedDepth) {/if (depth == rejectedDepth + 1) {/'

mutant never-skip-descendants \
    "descendants of a rejected node are evaluated on their own, so a passing child of a failing parent survives" \
    's/if (depth > rejectedDepth) {/if (false) {/'

mutant no-reset \
    "the skip state is never cleared after a rejected subtree ends" \
    '/rejectedDepth = NOT_SKIPPING; \/\/ the rejected/d'

mutant skip-depth-off-by-one \
    "records depth + 1 for a rejected node, so its direct children are kept" \
    's/rejectedDepth = depth;/rejectedDepth = depth + 1;/'

mutant calls-predicate-on-skipped \
    "predicate is also called on nodes inside rejected subtrees (wasted work, breaks the call contract)" \
    's|continue; // descendant of a rejected node|nodeIdPredicate.test(hierarchy.nodeId(i)); continue;|'

mutant inverted-predicate \
    "keeps the nodes the predicate rejects" \
    's/if (nodeIdPredicate.test(id)) {/if (!nodeIdPredicate.test(id)) {/'

mutant trim-off-by-one \
    "result arrays are trimmed to kept + 1, leaving a phantom node" \
    's/Arrays.copyOf(ids, kept)/Arrays.copyOf(ids, kept + 1)/; s/Arrays.copyOf(depths, kept)/Arrays.copyOf(depths, kept + 1)/'

mutant no-null-check-on-predicate \
    "removes the fail-fast null check on the predicate" \
    '/Objects.requireNonNull(nodeIdPredicate/d'

echo
echo "Killed: $killed   Survived: $survived   Invalid: $invalid"
if [ "$survived" -gt 0 ] || [ "$invalid" -gt 0 ]; then
    exit 1
fi
echo "Every mutant was caught by the test suite."
