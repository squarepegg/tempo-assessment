# Senior Developer Assessment

[![CI](https://github.com/squarepegg/tempo-assessment/actions/workflows/ci.yml/badge.svg)](https://github.com/squarepegg/tempo-assessment/actions/workflows/ci.yml)

Two tasks, three test suites, one command to see all of it:

```bash
./gradlew review      # runs every suite and prints one scoreboard (about 5 seconds, any JDK 17+)
```

Expected output (the 11 failures in `cacheProblems` are intended, see [Reading the results](#reading-the-results)):

```text
Suite                                          Gradle task      passed failed skipped
Task 1: HierarchyFilter                        test                 33      0       0
Task 2: problems in the reviewed SimpleCache   cacheProblems         1     11       1
Task 2: are the findings fair?                 cacheValidation      12      0       0
Everything is as designed.
```

## The map

| Task | Where | Gradle task | What it answers | Expected result |
|---|---|---|---|---|
| 1. Hierarchy filter | `src/main/java/Hierarchy.java` and `src/test/java/FilterTest.java` | `./gradlew test` | Is `filter()` correct? | **All 33 pass** |
| 2. Code review | [`SimpleCache.md`](SimpleCache.md) (the review itself) | | | |
| 2, evidence | `src/cacheProblems/java` | `./gradlew cacheProblems` | Does the reviewed `SimpleCache` meet the requirements a production cache must meet? | **11 fail (expected)**, each failure is a finding; 1 passes, 1 skipped |
| 2, evidence | `src/cacheValidation/java` | `./gradlew cacheValidation` | Are those failures fair, and can the simulation be trusted? | **All 12 pass** |
| (shared code) | `src/simplecache/java` | none, it has no tests | The reviewed class (verbatim), a fixed sketch, a virtual clock, the simulation engine | |

```text
src/main/java/Hierarchy.java          Task 1 solution
src/test/java/FilterTest.java         Task 1 tests
src/simplecache/java/                 the cache lab, not tests: SimpleCache (verbatim), ClockedSimpleCache, CorrectedCache,
                                      VirtualClock, CacheSimulation (the engine)
src/cacheProblems/java/               OriginalCacheRequirementsTest, OriginalCacheSimulationTest   <- the findings (red)
src/cacheValidation/java/             CorrectedSketchTest, SimulationTrustTest                     <- the fairness checks (green)
scripts/mutation-check.sh             shows Task 1's tests failing on deliberately broken code
```

Each test class lives in one folder and is run by one Gradle task, so an IDE's run action and the Gradle task always agree.

## Reading the results

`cacheProblems` is expected to fail 11 tests. Each one is a finding about the reviewed cache.

| Suite | A failing test means | The build passes when |
|---|---|---|
| `test` (Task 1) | the filter has a bug | 0 tests fail |
| `cacheProblems` | a finding: the cache fails a requirement | **exactly 11** tests fail |
| `cacheValidation` | the evidence is unfair or the simulation is broken | 0 tests fail |

Each suite declares its expected failure count in `build.gradle`, prints it before running, and prints a verdict afterwards. A different count fails the build, for example when a problem is fixed in the cache or a test breaks.

Test tasks never report `UP-TO-DATE`. Failures print their full message, including the step-by-step trace for the simulations.

## Running things

Requirements: **JDK 17 or newer** (tested on 17, 21 and 25). The Gradle 9.9.1 wrapper uses the JDK on `JAVA_HOME` / `PATH`. There is no toolchain or language-level pin.

```bash
./gradlew review                  # all three suites, then one scoreboard
./gradlew check                   # the same suites, standard Gradle name (no scoreboard)
./gradlew test                    # Task 1 only
./gradlew cacheProblems           # the findings (11 expected failures)
./gradlew cacheValidation         # the fairness checks (all green)
./scripts/mutation-check.sh       # Task 1: tests failing on deliberately broken code, ends "Killed: 9  Survived: 0"
./gradlew showExample -q          # prints the example forest above, generated from the real code
```

**CI:** GitHub Actions runs `review` on JDK 17, 21 and 25 and the mutation check on JDK 21. Each job's summary shows the scoreboard, the example forest and the mutation result, and uploads the HTML test reports as an artifact. See the [Actions tab](https://github.com/squarepegg/tempo-assessment/actions/workflows/ci.yml).

**One class or one test.** Use the task that owns the class (see the map). Filtered runs skip the expected-failure check.

```bash
./gradlew test --tests FilterTest
./gradlew test --tests 'FilterTest.rejectedLeafOnlyRemovesItself'
./gradlew cacheProblems --tests 'OriginalCacheRequirementsTest.hotKeyIsLoadedOnce'
./gradlew cacheValidation --tests 'SimulationTrustTest'
```

**Replay one simulation seed** (prints the full trace on the reviewed cache and on the fixed sketch):

```bash
./gradlew cacheProblems --tests 'OriginalCacheSimulationTest.replaySeedFromProperty' -Dsim.seed=41
```

**Reports:** the HTML report for each task is at `build/reports/tests/<task name>/index.html`.

**IDE:** import as a Gradle project. Each test class belongs to one Gradle task; the tasks are listed under `verification` in the Gradle tool window.

All code is in the default package, as in the original file.

## Task 1: `HierarchyFilter.filter`

A node survives iff its ID passes the predicate **and** all of its ancestors pass it.

### Approach

In DFS order a subtree is a contiguous run of nodes. It ends at the first following node whose depth is less than or equal to the subtree root's depth. So the filter is a single left-to-right pass:

```text
rejectedDepth = NOT_SKIPPING                   // Integer.MAX_VALUE: no depth is greater than it
for each index i:
    if depth(i) > rejectedDepth: skip it       // inside a rejected subtree; predicate NOT called
    else:
        rejectedDepth = NOT_SKIPPING           // any rejected subtree has ended
        if predicate(nodeId(i)): append (id, depth)
        else rejectedDepth = depth(i)          // start skipping its descendants
```

Worked example from the task (`id % 3 != 0`): 3 fails at depth 2, so 4 (depth 3) is skipped; 5 (depth 1) ends the run and is evaluated; 6 fails at depth 0, so 7 is skipped; 8 (depth 0) ends the run; 9 fails at depth 1; 10 (depth 1) ends the run. Result: `1, 2, 5, 8, 10, 11`.

The same example drawn as the `Hierarchy` Javadoc draws forests (one hyphen per level of depth). The diagram is generated by `./gradlew showExample -q` and a test checks that this README contains exactly that output:

```text
input (11 nodes)                      after filter(id % 3 != 0)
1                                     1
- 2                                   - 2
- - 3   <- rejected                   - 5
- - - 4   removed with an ancestor    8
- 5                                   - 10
6   <- rejected                       - - 11
- 7   removed with an ancestor
8
- 9   <- rejected
- 10
- - 11
```

| | |
|---|---|
| Time | O(n), a single pass |
| Extra space | O(1) state, plus the two result arrays (trimmed with `Arrays.copyOf` only if something was dropped) |
| Predicate calls | at most once per node, never for nodes under a rejected ancestor |
| Recursion | none, so depth is not limited by the stack (tested with a 500,000-deep chain) |

### Why it is correct

Let `D` be `rejectedDepth`. Invariant: before looking at index `i`, `D` is the depth of a rejected node `j < i` when every node strictly between `j` and `i` is deeper than `j`; otherwise `D = NOT_SKIPPING`.

1. **Skipped nodes are exactly the descendants of a rejected node.** Depth rises by at most 1 per step (the documented invariant), so the nodes after `j` that are deeper than `D` form an unbroken run of descendants. The first node with depth <= `D` is a sibling or a shallower node, never a descendant, so skipping stops there.
2. **One variable is enough for nested rejections.** A node is only rejected while we are *not* skipping, so a rejected node is always the outermost rejected node in its chain. Any rejection that would have been nested inside is never evaluated, and its subtree is inside the outer run anyway.
3. **Kept implies every ancestor passed.** If any ancestor had been rejected, the node would be inside that ancestor's run and skipped. So every ancestor was evaluated and passed.
4. **Dropped implies "failed, or has a failed ancestor".** There is no other way to be dropped.
5. **Depths do not change.** A kept node keeps its whole ancestor chain, so its depth is the same, and the output obeys the same depth invariants as the input.

### Alternatives considered

| Alternative | Why not |
|---|---|
| Build a tree, recurse, flatten again | Allocates per node, and overflows the stack on deep forests. |
| Keep a stack of "ancestor kept?" flags per depth | O(depth) extra space, and it still evaluates the predicate on nodes already known to be dropped. |
| Mark nodes in a boolean array, then compact | Extra memory and a second pass. |
| Count survivors first so the result arrays are exact-size | Needs the predicate to run twice, and it may be expensive or impure. I accept a transient allocation of up to 2n ints instead. |

### Assumptions

- The input follows the invariants in the `Hierarchy` Javadoc. They are **not** re-validated (that would cost another pass), so invalid input gives unspecified output.
- Node IDs are unique, as stated. The algorithm never compares IDs, it only passes them to the predicate.
- The predicate is pure and cheap. If it has side effects, they will not happen for nodes inside rejected subtrees.
- `null` arguments throw `NullPointerException` immediately, even when the hierarchy is empty.
- The result is a new `ArrayBasedHierarchy`. The input is not modified and no arrays are shared with it.
- `Hierarchy.size()` is an `int`, so a hierarchy has at most `Integer.MAX_VALUE` nodes.

### Tests (`src/test/java/FilterTest.java`)

Each test targets a specific way the algorithm could be wrong:

| Area | Cases |
|---|---|
| The provided example | unchanged |
| Degenerate inputs | empty, single node kept/rejected, everything passes, nothing passes |
| The defining rule | a passing child under a failing parent is removed; rejected root, middle node and leaf; only a deep descendant passes |
| Where a skipped subtree ends | at a sibling, at a shallower node, at a new tree, after a multi-level depth drop |
| Nested and repeated rejections | consecutive rejected siblings, a rejected node inside a rejected subtree |
| Robustness | extreme IDs (`MIN_VALUE`, `MAX_VALUE`), 500,000-deep chain, a `Hierarchy` that is not array-backed |
| Contract | predicate called at most once per node and never inside a rejected subtree; input not mutated; result does not alias input arrays; null arguments |
| Randomised | 2,000 seeded random forests compared with an independent reference implementation (parent pointers instead of skip depth), and the output is checked against the depth invariants. A failure prints its seed, so it can be replayed. |
| Metamorphic | Relations between two runs, with no reference implementation, over 1,000 seeded forests each: filtering with `p` then `q` equals filtering once with `p and q`; a looser predicate keeps everything a stricter one keeps; the output keeps input order and depths; and rejecting one more node removes exactly that node's subtree from the output. |
| Failure output | A failed forest comparison prints both forests as lists and drawn as trees (same hyphen format as the `Hierarchy` Javadoc), so a missing or surviving subtree is visible at a glance. The renderer is itself tested. |
| README diagram | The worked-example diagram in this README must equal what `./gradlew showExample -q` prints, so it cannot go stale. |

### Proof that the tests can fail: mutation check

Passing tests only mean something if they would fail on wrong code. `scripts/mutation-check.sh` copies the project to a temp directory, applies **one deliberate bug** to `filter()`, runs the suite there, and reports which tests caught it. Your working tree is never touched.

```bash
./scripts/mutation-check.sh
```

| Mutant | Bug introduced | Killed by (examples) |
|---|---|---|
| `boundary-inclusive` | `>` becomes `>=`, so a sibling of a rejected node is dropped | `forestOfOnlyRoots`, `consecutiveRejectedSiblingsAreAllRemoved` |
| `only-direct-children` | only direct children of a rejected node are dropped | `onlyDeepDescendantPassesButAncestorsFail`, random forests |
| `never-skip-descendants` | descendants are judged on their own, so a passing child of a failing parent survives | `parentFailsChildPassesIsStillRemoved` and many more |
| `no-reset` | skip state never cleared after the subtree ends | `testFilter`, `rejectedNodeRemovesItsWholeSubtreeEvenIfDescendantsPass` |
| `skip-depth-off-by-one` | records `depth + 1`, so direct children survive | `consecutiveRejectedSiblingsAreAllRemoved` and many more |
| `calls-predicate-on-skipped` | predicate also runs on skipped nodes | `predicateIsCalledAtMostOnce...` (only this test) |
| `inverted-predicate` | keeps what the predicate rejects | most of the suite |
| `trim-off-by-one` | result arrays one element too long | most of the suite |
| `no-null-check-on-predicate` | removes the fail-fast null check | `nullArgumentsAreRejected` (only this test) |

Expected final line: `Killed: 9   Survived: 0   Invalid: 0`. A surviving mutant would mean a blind spot in the tests, and the script exits non-zero. While writing this I found exactly such a gap: with a non-empty hierarchy, a null predicate fails anyway when it is first called, so the explicit null check was untested. The empty-hierarchy assertion in `nullArgumentsAreRejected` closes it.

The script also accepts a Gradle test filter, to see what a subset of the tests catches on its own: `./scripts/mutation-check.sh 'FilterTest.rejectingOneMore*'`. Measured this way, the metamorphic properties differ a lot. The subtree-removal property alone kills 7 of the 9 mutants (every semantic one; the two survivors are the predicate-call count and the null check, which it does not test). Composition, loosening and order/depth preservation kill 0-4 each, and none of them catches the ancestor rule, because a filter that judged every node on its own would satisfy them as well. They are kept as cheap consistency checks.

---

## Task 2: Code review of `SimpleCache`

Full review: [`SimpleCache.md`](SimpleCache.md), 15 numbered issues ordered by production impact, each with its impact and a fix, plus a section explaining **why I focused on these** and how I prioritised.

Short version of the reasoning: the class is thread-safe at the level of single calls (`ConcurrentHashMap`, immutable entries), so a data-race hunt finds little. The real production risks are what it does *not* do under the stated load: memory that only ever grows (no eviction, no bound), time measured with a clock that can jump, and no defence against a hot key expiring under thousands of reads per second.

### Executable evidence for the review (optional extra)

The main claims are also runnable tests, in two folders:

| Folder / task | Question | Result |
|---|---|---|
| `src/cacheProblems/java` / `./gradlew cacheProblems` | **Does the reviewed `SimpleCache` meet the requirements a production cache must meet?** | **11 tests fail (expected).** Each failure message is a bug report. 1 test passes (the cache returns correct values) and 1 is skipped (a seed replay). |
| `src/cacheValidation/java` / `./gradlew cacheValidation` | **Are those failures fair?** Do they fail because of the cache, or because of a flawed test? | **12 tests pass.** |

Everything they share (the reviewed class, a fixed sketch, the simulation engine) is in `src/simplecache/java`, which contains no tests.

#### 1. Findings: `cacheProblems` (11 red = 11 findings)

**Directed tests**, `OriginalCacheRequirementsTest`: one per issue, run against the reviewed class. No sleeping: time is virtual. The test name is the requirement.

| Review issue | Requirement (test method) | What the failure says | Reachable in production? |
|---|---|---|---|
| 1, 6 | `expiredEntriesAreFreed` | `expected: <0> but was: <10000>`: 10,000 entries expired a minute ago and are all still stored | Yes, always |
| 2 | `sizeIsBounded` | 500,000 entries stored after 500,000 distinct puts, no maximum | Yes |
| 3 | `entryOlderThanTtlIsNeverServed` | an entry written 2 hours ago was served, TTL is 1 minute | Yes, rare (NTP step, VM resume) |
| 3 | `liveEntryIsNotExpiredByAClockStep` | an entry written 0 ms ago already reads as expired | Yes, rare |
| 4 | `hotKeyIsLoadedOnce` | `expected: <1> but was: <50>`: 50 threads miss and all call the backend. Deterministic: a barrier opens only after all have missed | Yes. It tests the get/load/put pattern the class forces on callers, not a defect inside one method |
| 5 | `aValueWrittenDuringExpiryRemovalSurvives_whenTheNaiveFixIsApplied` | the obvious fix for issue 1 deletes a value written concurrently (interleaving forced by a hook, no timing luck) | Only if someone applies that fix. This targets the naive fix, not the reviewed class |
| 7 | `anAbsentResultCanBeCached` | `expected: <1> but was: <3>`: three lookups, three backend calls | Yes |
| 9 | `callersDoNotShareMutableState` | one caller's write is visible to another | Yes, if `V` is mutable |

**Simulation tests**, `OriginalCacheSimulationTest`: a seeded random sequence of `put`, `get`, `advance`, `evictExpired` and `stepWallClock` (the injected fault) against a virtual clock, checked against a tiny reference model. How it works is documented at the top of `src/simplecache/java/CacheSimulation.java`.

| Test | Result | What it means |
|---|---|---|
| `neverServesAnExpiredValue` | **fails** in 95 of 300 seeds | a value older than the TTL is served after the wall clock steps back |
| `neverExpiresALiveValueEarly` | **fails** in 59 of 300 seeds | a live value expires early after the wall clock steps forward |
| `everythingExpiredIsEventuallyFreed` | **fails** in 299 of 300 seeds | expired entries are never freed (the one clean seed has no puts) |
| `returnsTheRightValues` | passes, **on purpose** | the cache is correct about values, so the failures above are specific findings and not a blanket complaint |
| `replaySeedFromProperty` | skipped | runs with `./gradlew cacheProblems -Dsim.seed=N`; prints the full trace on the original and on the fixed sketch |

Each failing simulation test prints the smallest failing seed and the step-by-step trace that reproduces it.

#### 2. Fairness: `cacheValidation` (12 green)

These do not judge the reviewed cache. They show that the findings above come from the cache and not from the tests or the harness.

**Same requirement, fixed cache** (`CorrectedSketchTest`). Every red directed test above has a green twin with the same name:

| Requirement | Red on the reviewed cache (`cacheProblems`) | Green on the fixed sketch (`cacheValidation`) |
|---|---|---|
| expired entries are freed (1) | `OriginalCacheRequirementsTest.expiredEntriesAreFreed` | `CorrectedSketchTest.expiredEntriesAreFreed` |
| old entry never served after a backward clock step (3) | `...entryOlderThanTtlIsNeverServed` | `...entryOlderThanTtlIsNeverServed` |
| live entry not expired by a forward clock step (3) | `...liveEntryIsNotExpiredByAClockStep` | `...liveEntryIsNotExpiredByAClockStep` |
| hot key loaded once (4) | `...hotKeyIsLoadedOnce` | `...hotKeyIsLoadedOnce` (single-flight loading) |
| fresh value survives expiry removal (5) | `...aValueWrittenDuringExpiryRemovalSurvives_whenTheNaiveFixIsApplied` | `...aValueWrittenDuringExpiryRemovalSurvives` (conditional remove) |
| absent result unambiguous (7) | `...anAbsentResultCanBeCached` | `...nullValuesAreRejectedSoNullAlwaysMeansAbsent` (same goal, different route: null is rejected) |
| bounded size (2), no shared mutable state (9) | red | none: the sketch does not implement them |

**Can the simulation be trusted?** (`SimulationTrustTest`)

| Test | What it proves |
|---|---|
| `aCorrectedCachePassesEveryInvariantOnEverySeed` | The invariants are satisfiable: a fixed cache passes all 300 seeds, so the three simulation failures are not asking for the impossible. |
| `mutantOnTheWallClockIsCaughtByNeverServesExpired` | A broken variant (fixed cache reading the wall clock) IS caught by `NeverServesExpired`. The invariant can see the fault it claims to. |
| `mutantWithoutCleanupIsCaughtByNoLeakAtQuiescence` | A variant with cleanup disabled IS caught by `NoLeakAtQuiescence`. |
| `mutantThatIgnoresOverwritesIsCaughtByReturnsLatestPut` | A variant that keeps the first value IS caught by `ReturnsLatestPut`. This invariant passes on the original, so this is the only place it is shown to work. |
| `theSameSeedReplaysIdentically` | The same seed gives the same trace every time, so "replay with `-Dsim.seed=N`" can be trusted. It would fail if the simulation used outside randomness or the real clock. |
| `theGeneratorReachesTheRareStatesItDeclares` | Every rare state the generator declares (a clock step while an entry is live, a read exactly at the TTL boundary, an overwrite, cleanup with expired entries) is reached by some seed. Without this, a sweep could pass by never visiting the interesting states. |

### Limits of this evidence

- **The class under review is not modified.** `SimpleCache.java` in `src/simplecache/java` is a verbatim copy, used by the tests that need no clock (issues 2, 4, 7, 9). Time tests use `ClockedSimpleCache`, which has exactly two changes: an injected clock and an injected TTL. The original hard-codes both (its TTL is a compile-time constant, so even reflection cannot change it; that is review issue 11). The copy can drift from the original; the diff is small enough to read in one minute.
- **Not simulated:** real thread scheduling (the stampede test forces its interleaving with a barrier), memory and GC, real NTP behaviour.
- **`CorrectedCache` is a sketch to prove the requirements can be met**, not production code: no size bound, no TTL jitter, no metrics. Production answer: Caffeine, as in `SimpleCache.md`.
