/**
 * Deterministic time for tests. Two clocks, because production has two:
 *
 * <ul>
 *   <li><b>true / monotonic time</b> ({@link #monotonicNanos()}): what {@code System.nanoTime()} measures. Only moves
 *       when a test calls {@link #advance(long)}.</li>
 *   <li><b>wall-clock time</b> ({@link #wallMillis()}): what {@code System.currentTimeMillis()} returns. It follows
 *       true time, plus whatever steps (NTP correction, manual change, VM resume) a test injects with
 *       {@link #stepWall(long)}.</li>
 * </ul>
 *
 * Everything is whole milliseconds, so the millisecond truncation of the wall clock cannot create false differences.
 */
final class VirtualClock {
    private static final long WALL_BASE_MS = 1_700_000_000_000L;

    private long trueMs;
    private long skewMs;

    long monotonicNanos() {
        return trueMs * 1_000_000L;
    }

    long trueMillis() {
        return trueMs;
    }

    long wallMillis() {
        return WALL_BASE_MS + trueMs + skewMs;
    }

    void advance(long ms) {
        if (ms < 0) throw new IllegalArgumentException("time only moves forward; use stepWall to move the wall clock");
        trueMs += ms;
    }

    /** Moves only the wall clock; true time is unaffected. */
    void stepWall(long ms) {
        skewMs += ms;
    }
}
