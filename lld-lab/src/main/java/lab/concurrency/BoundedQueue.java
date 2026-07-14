package lab.concurrency;

/**
 * Exercise 01 — bounded blocking queue.
 *
 * Contract: put blocks while full, take blocks while empty; both are
 * interruptible. Implementations must survive N producers x M consumers
 * without losing or duplicating elements.
 */
public interface BoundedQueue<T> {

    void put(T item) throws InterruptedException;

    T take() throws InterruptedException;

    int size();
}
