## Code Review

Load assumed: thousands of reads/s, hundreds of writes/s, tens of threads.

First, what is fine: `ConcurrentHashMap` gives thread-safe `get`/`put`, and `CacheEntry` has only `final` fields, so entries are safely published to other threads. The problems are what the class does not do.

Ordered by how likely they are to hurt in production.

### Why these issues, and how I prioritised

The brief names the load (thousands of reads/s, hundreds of writes/s, tens of threads) and asks for issues that must be fixed *before deploying*. So I asked three questions of every line, in this order:

- **Is it thread-safe?** Mostly yes: `ConcurrentHashMap` makes single calls atomic and `CacheEntry` is immutable with `final` fields, so there is no torn read and no unsafe publication. This is why the list has few classic "data race" items. The remaining thread-safety risks are about *sequences* of calls and about the cached values themselves (items 5, 8, 9).
- **What happens over time under that load?** A cache's characteristic failures are slow ones: memory that only grows (1, 2), and time handling that drifts or jumps (3). They pass every unit test and every short load test, and fail in production after hours or days. That is why they come first.
- **What happens at the moment a hot key expires?** At thousands of reads/s a single expiry is a burst of identical backend calls (4). This is the failure that turns a cache from an optimisation into an outage, because the backend sees *more* load at exactly the wrong time.

Everything after that is about whether the class can be operated and safely evolved: correct behaviour of `size()`, null semantics, invalidation, observability, testability.

What I deliberately left out: micro-optimisations. At hundreds of writes/s the extra `CacheEntry` allocation per `put` is noise, and `ConcurrentHashMap` reads are already lock-free. Listing them would dilute the findings that matter.


### Must fix before deploy

1. **Expired entries are never removed (memory leak).**
   `get()` returns `null` for an expired entry but leaves it in the map. Nothing else ever removes it. Any key that is not read again after it expires stays forever.
   *Impact:* heap grows monotonically with the number of distinct keys ever written. At hundreds of writes/s with mostly distinct keys that is millions of new entries per day, none of which are ever freed. Longer GC pauses, then `OutOfMemoryError`.
   *Fix:* evict on a schedule or lazily on access, using a conditional remove (see 5), or use a library cache (see "Recommendation").

2. **No size bound.**
   Even with expiry fixed, there is no maximum. A burst of distinct keys (a scan, a bad client, a bug) fills the heap for the next 60 seconds before anything could expire.
   *Impact:* memory use is controlled by traffic, not by configuration. One noisy client can take the service down.
   *Fix:* a maximum size with an eviction policy (LRU or a frequency-aware policy such as W-TinyLFU).

3. **`System.currentTimeMillis()` is the wrong clock for measuring elapsed time.**
   It is wall-clock time. NTP corrections, manual changes, VM pause/resume or leap-second handling can move it backwards or forwards.
   *Impact:* if the clock steps back, `now - timestamp` goes negative or small, so entries that should have expired are served for much longer than 60 s (stale data). If it steps forward, the whole cache expires at once (see 4).
   *Fix:* `System.nanoTime()` (monotonic) for the age check.

4. **No protection against a cache stampede (dogpile) or an expiry avalanche.**
   The class is a plain get/put. On a miss the caller loads from the source and calls `put`.
   *Stampede:* when a popular key expires, every thread that reads it in that window misses and loads it at the same time.
   *Avalanche:* entries written together all expire together, so many keys miss at once.
   *Impact:* at thousands of reads/s on a hot key, one expiry becomes tens or hundreds of identical calls to the database or downstream service, and the load spikes again every 60 s. This is the usual way a cache turns into an outage, because the backend sees more load exactly when it is least able to take it.
   *Fix:* single-flight loading for the stampede (one thread loads, others wait for the result), for example `ConcurrentHashMap.compute`/`computeIfAbsent` or a library `LoadingCache`; random jitter on the TTL for the avalanche; consider refresh-ahead so readers keep getting the old value while a new one loads. Caveat for `compute`/`computeIfAbsent`: the loader runs while a bin lock is held, so it must be fast and must not touch the map; for slow or blocking loads, store a per-key future (`CompletableFuture`) or use Caffeine, which handles this.

### Should fix

5. **Any fix for 1 must be a conditional remove.**
   The obvious fix, `if expired then cache.remove(key)` in `get()`, has a race: between the expiry check and the `remove`, another thread can `put` a fresh value, which then gets deleted.
   *Impact:* new data is silently thrown away, which looks like random cache misses under write load.
   *Fix:* `cache.remove(key, entry)` (removes only if the mapped value is still the same entry) or `cache.computeIfPresent`.

6. **`size()` is misleading.**
   It counts expired-but-not-removed entries, so it reports memory held, not usable entries. It is also only an estimate while other threads are writing (`ConcurrentHashMap.size()` is not a snapshot).
   *Impact:* dashboards and capacity decisions based on it are wrong; tests asserting on it are flaky under concurrency.
   *Fix:* document it as an approximation, or expose separate "live" and "stored" counts.

7. **Null values are ambiguous and null keys fail late.**
   `put(k, null)` stores an entry whose value is `null`. `get` then returns `null`, which looks exactly like a miss, so callers reload every time and there is no way to cache "not found". A `null` key throws `NullPointerException` from inside the map, with no documented contract.
   *Impact:* hidden extra load for negative lookups; surprising exceptions.
   *Fix:* reject null values up front (`Objects.requireNonNull`) or return `Optional<V>`; if negative caching is wanted, store an explicit sentinel with its own (shorter) TTL.

8. **No invalidation or atomic update API.**
   There is no `remove`, `clear`, `putIfAbsent` or `compute`. When the source of truth changes, the only option is to wait up to 60 s, and callers who need read-modify-write must do `get` then `put`, which is not atomic.
   *Impact:* stale reads after writes elsewhere in the system; lost updates and duplicate loads from racing `get`/`put` pairs.
   *Fix:* add `invalidate(key)`, `invalidateAll()`, and an atomic `computeIfAbsent`-style method.

9. **Cached values are shared references.**
   `get` hands every caller the same object. If `V` is mutable, one thread's change is seen by the others without any synchronization. `CacheEntry` being final does not make `V` safe.
   *Impact:* data races, torn or inconsistent state, bugs that are very hard to reproduce.
   *Fix:* require immutable values (document it, or constrain the type), or copy defensively. The same applies to keys: they must have a stable `hashCode`/`equals`, otherwise entries are lost or leaked.

10. **No observability.**
    No hit/miss counts, evictions, load time, or size over time.
    *Impact:* you cannot tell whether the TTL is right, whether the hit rate is collapsing, or why latency changed.
    *Fix:* counters/timers exported to the metrics system (Micrometer, Prometheus, Datadog).

### Lower priority

11. **TTL is hard-coded and the clock cannot be injected.** `ttlMs = 60000` is a constant in the class, and time comes from a static call. *Impact:* no per-use-case TTL, and expiry behaviour cannot be unit-tested without sleeping. *Fix:* constructor parameters for TTL and a `Ticker`/`Clock`/`LongSupplier`.
12. **Only expire-after-write.** `timestamp` is set on `put`, so an entry that is read constantly still expires 60 s after it was written. *Impact:* the hottest keys get a guaranteed miss every minute, which feeds the stampede in 4; if sliding expiry was intended instead, the behaviour is simply wrong. *Fix:* document the policy, or choose expire-after-access or refresh-ahead explicitly.
13. **Public nested `CacheEntry`.** *Impact:* callers can depend on the nested type and its `getTimestamp()`, so the representation (and the clock it implies, see 3) cannot change without breaking them. *Fix:* make it `private` (a `record` in Java 16+).
14. **Default table size.** `new ConcurrentHashMap<>()` starts at 16 bins and resizes as it fills. *Impact:* extra allocation and rehashing while serving traffic during warm-up, visible as latency spikes. *Fix:* pre-size for the expected number of entries once a bound exists (see 2).
15. **No lifecycle.** *Impact:* once a background cleaner exists (see 1), a non-daemon or unnamed thread keeps the JVM alive on shutdown and leaks across redeploys, and an uncaught exception silently stops cleanup, which brings back the leak in 1. *Fix:* an explicit `close()`, a named daemon thread (or a shared scheduler), and error handling inside the cleanup task.

### Recommendation

Do not maintain this by hand. Everything above is solved, and tested at much higher load than ours, by a library cache. For example with Caffeine:

```java
LoadingCache<K, V> cache = Caffeine.newBuilder()
    .maximumSize(100_000)                    // 2
    .expireAfterWrite(Duration.ofSeconds(60)) // 1, 3 (uses nanoTime)
    .refreshAfterWrite(Duration.ofSeconds(45)) // 4: serve old value while reloading
    .recordStats()                            // 10
    .build(key -> loadFromSource(key));       // 4: single-flight per key, 7: null not allowed
```

If a hand-written class is required, the minimum is: bounded size, `nanoTime`, conditional removal, single-flight loading with jittered TTL, an injectable clock, and metrics.

### Evidence

The main claims above are also runnable tests, in two folders that answer two questions. `./gradlew cacheProblems` runs the requirements a production cache must meet against the reviewed class: 11 tests fail on purpose, and each failure message is a bug report (time is virtual, so nothing sleeps). A seeded simulation finds the clock and memory problems (issues 1 and 3) on its own and prints the smallest failing trace; directed tests cover issues 1-5, 7 and 9. `./gradlew cacheValidation` (all green) shows the failures are fair: the same requirements pass on a corrected sketch, broken variants of that sketch are caught by the invariants, and the simulation is deterministic. `./gradlew review` runs everything and prints one scoreboard. See the README for the map, the tables and the limits (the class is copied with only an injected clock and TTL; the stampede test forces its interleaving with a barrier).
