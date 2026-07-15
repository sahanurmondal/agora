package lab.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 02 — thread pool from scratch, on top of exercise 01's queue.
 *
 * The traps this dodges:
 * 1. Shutdown race: a submit racing shutdown() must either run or throw
 *    RejectedExecutionException — never silently vanish. The gate is a
 *    synchronized state check-and-enqueue, so the flag flip and the enqueue
 *    cannot interleave.
 * 2. Worker death: an uncaught exception from a task must not shrink the
 *    pool. Workers catch Throwable from tasks; only the poison pill exits.
 * 3. Drain semantics: shutdown() = finish queued work (one poison pill per
 *    worker, AFTER the flag, so pills queue behind real work);
 *    shutdownNow() = interrupt everyone, abandon the queue.
 */
public final class SimpleThreadPool {

    private static final Runnable POISON = () -> {
    };

    private final BoundedQueue<Runnable> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger executed = new AtomicInteger();
    private boolean shutdown; // guarded by 'this'

    public SimpleThreadPool(int threads, int queueCapacity) {
        this.queue = new LockConditionBoundedQueue<>(queueCapacity);
        for (int i = 0; i < threads; i++) {
            Thread w = Thread.ofPlatform().name("pool-worker-" + i).start(this::workerLoop);
            workers.add(w);
        }
    }

    public synchronized void submit(Runnable task) throws InterruptedException {
        // Check AND enqueue under one monitor: otherwise a shutdown between the
        // check and the put lands this task BEHIND the poison pills — accepted
        // but never executed (the exact race the stress test hunts). Blocking on
        // a full queue while holding the monitor is safe: workers drain without
        // taking this lock, so the put always makes progress.
        if (shutdown) {
            throw new RejectedExecutionException("pool is shut down");
        }
        queue.put(task);
    }

    /** Graceful: reject new work, finish everything queued, then stop workers. */
    public synchronized void shutdown() throws InterruptedException {
        if (shutdown) return;
        shutdown = true;
        for (int i = 0; i < workers.size(); i++) {
            queue.put(POISON);
        }
    }

    /** Abrupt: interrupt workers; queued tasks are abandoned. */
    public synchronized void shutdownNow() {
        shutdown = true;
        workers.forEach(Thread::interrupt);
    }

    public void awaitTermination() throws InterruptedException {
        for (Thread w : workers) {
            w.join();
        }
    }

    public int executedCount() {
        return executed.get();
    }

    private void workerLoop() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                return; // shutdownNow
            }
            if (task == POISON) {
                return;
            }
            try {
                task.run();
                executed.incrementAndGet();
            } catch (Throwable t) {
                executed.incrementAndGet(); // counted as executed-with-error; worker survives
            }
        }
    }
}
