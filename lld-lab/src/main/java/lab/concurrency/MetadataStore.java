package lab.concurrency;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * Exercise 06 — read-mostly store, three locking disciplines.
 *
 * The traps: (1) read→write lock UPGRADE on ReentrantReadWriteLock
 * self-deadlocks (must release read before acquiring write); (2) non-fair
 * RW-lock can starve writers under heavy reads; (3) StampedLock's optimistic
 * read costs nothing when uncontended but MUST validate() and fall back —
 * and it is not reentrant.
 *
 * Benchmark: RwLockBenchmark (JMH, 95/5 read/write mix).
 */
public interface MetadataStore {

    String get(String key);

    void put(String key, String value);

    final class Synchronized implements MetadataStore {
        private final Map<String, String> map = new HashMap<>();

        @Override
        public synchronized String get(String key) {
            return map.get(key);
        }

        @Override
        public synchronized void put(String key, String value) {
            map.put(key, value);
        }
    }

    final class RwLock implements MetadataStore {
        private final Map<String, String> map = new HashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        @Override
        public String get(String key) {
            lock.readLock().lock();
            try {
                return map.get(key);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void put(String key, String value) {
            lock.writeLock().lock();
            try {
                map.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    final class Stamped implements MetadataStore {
        private final Map<String, String> map = new HashMap<>();
        private final StampedLock lock = new StampedLock();

        @Override
        public String get(String key) {
            long stamp = lock.tryOptimisticRead();
            String value = map.get(key);
            if (!lock.validate(stamp)) { // a writer slipped in: retry pessimistically
                stamp = lock.readLock();
                try {
                    value = map.get(key);
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return value;
        }

        @Override
        public void put(String key, String value) {
            long stamp = lock.writeLock();
            try {
                map.put(key, value);
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }
}
