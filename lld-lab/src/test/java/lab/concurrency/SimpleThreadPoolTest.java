package lab.concurrency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleThreadPoolTest {

    @Test
    @Timeout(60)
    void noTaskSilentlyDropped_underConcurrentShutdown() throws Exception {
        SimpleThreadPool pool = new SimpleThreadPool(4, 64);
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        int submitters = 8, perThread = 25_000;
        CountDownLatch done = new CountDownLatch(submitters);

        for (int s = 0; s < submitters; s++) {
            Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        try {
                            pool.submit(() -> {
                            });
                            submitted.incrementAndGet();
                        } catch (RejectedExecutionException e) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(50); // let work start, then shut down mid-stream
        pool.shutdown();
        done.await();
        pool.awaitTermination();

        // The invariant: every attempted submit either executed or was rejected.
        assertEquals(submitted.get(), pool.executedCount(),
                "accepted tasks must all execute (graceful drain)");
        assertEquals(submitters * perThread, submitted.get() + rejected.get());
        assertTrue(rejected.get() > 0, "shutdown mid-stream should reject some");
    }

    @Test
    @Timeout(20)
    void workerSurvivesThrowingTask() throws Exception {
        SimpleThreadPool pool = new SimpleThreadPool(2, 16);
        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                throw new RuntimeException("boom");
            });
        }
        CountDownLatch after = new CountDownLatch(1);
        pool.submit(after::countDown);
        assertTrue(after.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "pool must keep serving after task exceptions");
        pool.shutdown();
        pool.awaitTermination();
        assertEquals(11, pool.executedCount());
    }
}
