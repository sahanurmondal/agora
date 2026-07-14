package lab.concurrency;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Variant A: intrinsic lock + wait/notifyAll.
 *
 * The two classic traps this implementation dodges:
 *
 * 1. The wait MUST sit in a while-loop, not an if. A woken thread re-acquires
 *    the monitor and may find the condition already consumed by another woken
 *    thread (stolen signal), and the JVM permits spurious wakeups.
 *
 * 2. notifyAll, not notify. With producers AND consumers waiting on the same
 *    monitor, a single notify can wake a producer when only a consumer can
 *    make progress — both then wait forever (lost-wakeup deadlock).
 */
public final class WaitNotifyBoundedQueue<T> implements BoundedQueue<T> {

    private final Deque<T> items = new ArrayDeque<>();
    private final int capacity;

    public WaitNotifyBoundedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    @Override
    public synchronized void put(T item) throws InterruptedException {
        while (items.size() == capacity) {
            wait();
        }
        items.addLast(item);
        notifyAll();
    }

    @Override
    public synchronized T take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        T item = items.removeFirst();
        notifyAll();
        return item;
    }

    @Override
    public synchronized int size() {
        return items.size();
    }
}
