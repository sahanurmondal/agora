package lab.concurrency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class Exercises03to07Test {

    // ---------- 03: striped LRU ----------

    @Test
    @Timeout(30)
    void lru_boundedAndCoherent_underConcurrency() throws InterruptedException {
        StripedLruCache<Integer, Integer> cache = new StripedLruCache<>(1024, 16);
        int threads = 8, opsPerThread = 200_000;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong hits = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread.ofPlatform().start(() -> {
                var rnd = new java.util.Random(seed);
                for (int i = 0; i < opsPerThread; i++) {
                    int k = rnd.nextInt(4096);
                    if (rnd.nextBoolean()) {
                        cache.put(k, k * 2);
                    } else {
                        Integer v = cache.get(k);
                        if (v != null) {
                            assertEquals(k * 2, v, "get-after-put coherence");
                            hits.incrementAndGet();
                        }
                    }
                }
                done.countDown();
            });
        }
        assertTrue(done.await(25, TimeUnit.SECONDS));
        assertTrue(cache.size() <= 1024 + 16, "capacity bound (±1 per segment in flight)");
        assertTrue(hits.get() > 0);
    }

    @Test
    @Timeout(30)
    void lru_stripedOutperformsGlobalLock() throws InterruptedException {
        // Not JMH — a directional throughput probe (JMH is the follow-up refinement).
        long striped = lruThroughput(new StripedLruCache<>(1024, 16));
        long global = lruThroughput(new StripedLruCache<>(1024, 1)); // 1 stripe == global lock
        System.out.printf("LRU ops/sec: striped(16)=%d global(1)=%d (%.1fx)%n",
                striped, global, (double) striped / global);
        // Direction, not magnitude: laptop CI variance makes exact ratios flaky.
        assertTrue(striped > global, "striping should beat a global lock under contention");
    }

    private long lruThroughput(StripedLruCache<Integer, Integer> cache) throws InterruptedException {
        int threads = 8;
        long durationMs = 500;
        AtomicLong ops = new AtomicLong();
        CountDownLatch done = new CountDownLatch(threads);
        long deadline = System.currentTimeMillis() + durationMs;
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread.ofPlatform().start(() -> {
                var rnd = new java.util.Random(seed);
                while (System.currentTimeMillis() < deadline) {
                    int k = rnd.nextInt(4096);
                    if ((k & 3) == 0) cache.put(k, k);
                    else cache.get(k);
                    ops.incrementAndGet();
                }
                done.countDown();
            });
        }
        done.await();
        return ops.get() * 1000 / durationMs;
    }

    // ---------- 04: token bucket ----------

    @Test
    void tokenBucket_refillMathIsDeterministic() {
        AtomicLong fakeNanos = new AtomicLong(0);
        TokenBucketLimiter limiter = new TokenBucketLimiter(10, 5, fakeNanos::get); // 10/s, burst 5

        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire(), "burst " + i);
        assertFalse(limiter.tryAcquire(), "bucket empty");

        fakeNanos.addAndGet(300_000_000L); // +300ms → +3 tokens
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "exactly 3 refilled, not 4");

        fakeNanos.addAndGet(10_000_000_000L); // +10s → cap at burst, not 100
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "refill must cap at burst");
    }

    @Test
    @Timeout(20)
    void tokenBucket_neverOverAdmitsUnderConcurrency() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(50, 100);
        int threads = 32, attemptsPer = 100;
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().start(() -> {
                for (int i = 0; i < attemptsPer; i++) {
                    if (limiter.tryAcquire()) admitted.incrementAndGet();
                }
                done.countDown();
            });
        }
        done.await();
        double elapsedSec = (System.nanoTime() - t0) / 1e9;
        long maxAllowed = (long) (100 + 50 * elapsedSec) + 1;
        assertTrue(admitted.get() <= maxAllowed,
                "admitted " + admitted.get() + " > burst+rate*t=" + maxAllowed);
    }

    // ---------- 05: connection pool ----------

    @Test
    @Timeout(30)
    void pool_capacityInvariant_andLeakDetection() throws InterruptedException {
        AtomicInteger created = new AtomicInteger();
        ConnectionPool<Object> pool = new ConnectionPool<>(4, () -> {
            created.incrementAndGet();
            return new Object();
        });

        int threads = 16;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger inUsePeak = new AtomicInteger();
        AtomicInteger inUse = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        Object c = pool.borrow(5, TimeUnit.SECONDS);
                        assertNotNull(c);
                        int now = inUse.incrementAndGet();
                        inUsePeak.accumulateAndGet(now, Math::max);
                        inUse.decrementAndGet();
                        pool.release(c);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(25, TimeUnit.SECONDS));
        assertTrue(inUsePeak.get() <= 4, "capacity breached: " + inUsePeak.get());
        assertTrue(created.get() <= 4, "factory over-created: " + created.get());

        // Deliberate leak: borrow and never release → detector names the borrower.
        Object leaked = pool.borrow(1, TimeUnit.SECONDS);
        Thread.sleep(60);
        var leaks = pool.leaks(50);
        assertEquals(1, leaks.size());
        assertTrue(leaks.values().iterator().next().borrowerStack().contains("Exercises03to07Test"));
        assertThrows(IllegalStateException.class, () -> pool.release(new Object()), "foreign release");
        pool.release(leaked);
    }

    // ---------- 07: deadlock ----------

    @Test
    @Timeout(30)
    void deadlock_detectedInNaive_impossibleInOrdered() throws InterruptedException {
        // Ordered version: opposing transfer storm completes, money conserved.
        var a = new DeadlockDemo.Account(1, 10_000);
        var b = new DeadlockDemo.Account(2, 10_000);
        CountDownLatch done = new CountDownLatch(2);
        Thread t1 = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 2_000; i++) DeadlockDemo.transferOrdered(a, b, 1);
            done.countDown();
        });
        Thread t2 = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 2_000; i++) DeadlockDemo.transferOrdered(b, a, 1);
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "ordered locking must not deadlock");
        assertEquals(20_000, a.balance + b.balance, "conservation");

        // Naive version: force the deadlock on daemon threads, detect via JMX.
        var c = new DeadlockDemo.Account(3, 100);
        var d = new DeadlockDemo.Account(4, 100);
        Thread n1 = Thread.ofPlatform().daemon().start(() -> DeadlockDemo.transferNaive(c, d, 1));
        Thread n2 = Thread.ofPlatform().daemon().start(() -> DeadlockDemo.transferNaive(d, c, 1));
        n1.join(500);
        n2.join(500); // give them time to interlock

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mx.findDeadlockedThreads();
        assertNotNull(deadlocked, "JMX must observe the circular wait");
        List<Long> ids = List.of(n1.threadId(), n2.threadId());
        long matches = java.util.Arrays.stream(deadlocked).filter(ids::contains).count();
        assertEquals(2, matches, "both naive threads are in the cycle");
        // Daemon threads: the deadlocked pair dies with the JVM, tests proceed.
    }
}
