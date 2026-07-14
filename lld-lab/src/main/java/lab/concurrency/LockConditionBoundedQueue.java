package lab.concurrency;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Variant B: ReentrantLock + two Conditions.
 *
 * Why this beats wait/notifyAll: separate notFull/notEmpty wait-sets mean a
 * producer signals ONLY consumers and vice versa — no herd of irrelevant
 * threads waking to re-check a condition they can't satisfy (that wasted
 * wake-recheck cycle is exactly what notifyAll costs under contention).
 * signal() (not signalAll) is safe here because each signal targets the one
 * wait-set that can consume it.
 */
public final class LockConditionBoundedQueue<T> implements BoundedQueue<T> {

    private final Deque<T> items = new ArrayDeque<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public LockConditionBoundedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    @Override
    public void put(T item) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (items.size() == capacity) {
                notFull.await();
            }
            items.addLast(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (items.isEmpty()) {
                notEmpty.await();
            }
            T item = items.removeFirst();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return items.size();
        } finally {
            lock.unlock();
        }
    }
}
