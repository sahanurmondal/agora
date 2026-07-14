package lab.concurrency;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedQueueStressTest {

    static final int PRODUCERS = 8;
    static final int CONSUMERS = 8;
    static final int PER_PRODUCER = 100_000;
    static final int TOTAL = PRODUCERS * PER_PRODUCER;
    static final int CAPACITY = 64; // small on purpose: forces both full and empty waits

    static List<Function<Integer, BoundedQueue<Integer>>> implementations() {
        return List.of(WaitNotifyBoundedQueue::new, LockConditionBoundedQueue::new);
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @Timeout(60)
    void multiProducerConsumer_noLossNoDuplicates(Function<Integer, BoundedQueue<Integer>> factory)
            throws InterruptedException {
        BoundedQueue<Integer> queue = factory.apply(CAPACITY);
        AtomicIntegerArray seen = new AtomicIntegerArray(TOTAL);
        AtomicLong claimed = new AtomicLong();
        CountDownLatch done = new CountDownLatch(PRODUCERS + CONSUMERS);
        List<Throwable> failures = new ArrayList<>();

        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            int base = p * PER_PRODUCER;
            threads.add(Thread.ofPlatform().unstarted(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++) {
                        queue.put(base + i);
                    }
                } catch (Throwable t) {
                    synchronized (failures) { failures.add(t); }
                } finally {
                    done.countDown();
                }
            }));
        }
        for (int c = 0; c < CONSUMERS; c++) {
            threads.add(Thread.ofPlatform().unstarted(() -> {
                try {
                    // Claim-then-take: each claim below TOTAL is guaranteed a
                    // matching element eventually, so consumers exit cleanly
                    // without poison pills.
                    while (claimed.getAndIncrement() < TOTAL) {
                        int value = queue.take();
                        if (!seen.compareAndSet(value, 0, 1)) {
                            throw new AssertionError("duplicate delivery: " + value);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) { failures.add(t); }
                } finally {
                    done.countDown();
                }
            }));
        }

        threads.forEach(Thread::start);
        assertTrue(done.await(50, java.util.concurrent.TimeUnit.SECONDS), "stress run timed out");
        assertEquals(List.of(), failures);

        for (int i = 0; i < TOTAL; i++) {
            if (seen.get(i) != 1) {
                throw new AssertionError("lost element: " + i);
            }
        }
        assertEquals(0, queue.size());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @Timeout(10)
    void putBlocksWhenFull(Function<Integer, BoundedQueue<Integer>> factory) throws InterruptedException {
        BoundedQueue<Integer> queue = factory.apply(1);
        queue.put(1);

        Thread blocked = Thread.ofPlatform().start(() -> {
            try {
                queue.put(2);
            } catch (InterruptedException ignored) {
            }
        });
        Thread.sleep(100);
        assertEquals(Thread.State.WAITING, blocked.getState());

        assertEquals(1, queue.take()); // frees capacity, unblocks the producer
        blocked.join(5_000);
        assertEquals(2, queue.take());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    @Timeout(10)
    void takeIsInterruptible(Function<Integer, BoundedQueue<Integer>> factory) throws InterruptedException {
        BoundedQueue<Integer> queue = factory.apply(1);
        AtomicLong interrupted = new AtomicLong();

        Thread blocked = Thread.ofPlatform().start(() -> {
            assertThrows(InterruptedException.class, queue::take);
            interrupted.incrementAndGet();
        });
        Thread.sleep(100);
        blocked.interrupt();
        blocked.join(5_000);
        assertEquals(1, interrupted.get());
    }
}
