package lab.concurrency;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Exercise 05 — connection pool: Semaphore caps capacity, a deque holds idle
 * connections, and a leak detector remembers WHO borrowed (stack + timestamp).
 *
 * Traps: (1) if creating a connection throws after acquire(), the permit must
 * be returned or capacity leaks forever (try/catch releases). (2) double
 * release would put one connection in the pool twice — the borrowed-set check
 * rejects it. (3) leak detection: real pools (HikariCP leakDetectionThreshold)
 * flag connections held past a deadline and name the borrower's stack.
 */
public final class ConnectionPool<C> {

    public record Leak(long heldMs, String borrowerStack) {
    }

    private final Supplier<C> factory;
    private final Semaphore permits;
    private final ArrayDeque<C> idle = new ArrayDeque<>();
    private final Map<C, BorrowRecord> borrowed = new HashMap<>();

    private record BorrowRecord(long atMs, String stack) {
    }

    public ConnectionPool(int capacity, Supplier<C> factory) {
        this.factory = factory;
        this.permits = new Semaphore(capacity, true);
    }

    public C borrow(long timeout, TimeUnit unit) throws InterruptedException {
        if (!permits.tryAcquire(timeout, unit)) {
            return null; // caller decides: fail fast / shed load
        }
        try {
            C conn;
            synchronized (this) {
                conn = idle.pollFirst();
            }
            if (conn == null) {
                conn = factory.get(); // may throw → permit released below
            }
            synchronized (this) {
                borrowed.put(conn, new BorrowRecord(System.currentTimeMillis(), captureStack()));
            }
            return conn;
        } catch (RuntimeException e) {
            permits.release(); // the classic capacity leak, plugged
            throw e;
        }
    }

    public synchronized void release(C conn) {
        if (borrowed.remove(conn) == null) {
            throw new IllegalStateException("double release or foreign connection");
        }
        idle.addFirst(conn);
        permits.release();
    }

    /** Leak scan: anything held longer than the threshold, with its borrower. */
    public synchronized Map<C, Leak> leaks(long thresholdMs) {
        long now = System.currentTimeMillis();
        Map<C, Leak> out = new HashMap<>();
        borrowed.forEach((conn, rec) -> {
            if (now - rec.atMs() > thresholdMs) {
                out.put(conn, new Leak(now - rec.atMs(), rec.stack()));
            }
        });
        return out;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    private static String captureStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(frames.length, 8); i++) {
            sb.append(frames[i]).append('\n');
        }
        return sb.toString();
    }
}
