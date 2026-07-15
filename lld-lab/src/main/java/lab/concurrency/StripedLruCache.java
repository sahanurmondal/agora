package lab.concurrency;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exercise 03 — thread-safe LRU cache.
 *
 * The trap: get() MUTATES recency order, so it is a write — a ReadWriteLock
 * buys nothing (every op needs the write lock) and one global lock serializes
 * all traffic. The classic escape is LOCK STRIPING: N independent segments,
 * each its own LinkedHashMap(accessOrder=true) + lock; contention drops ~N×
 * for uniformly distributed keys. The trade-offs striping accepts:
 * per-segment (not global) LRU eviction and no cheap global size snapshot —
 * exactly why Caffeine abandons strict LRU for W-TinyLFU with lossy buffers.
 */
public final class StripedLruCache<K, V> {

    private final Segment<K, V>[] segments;

    @SuppressWarnings("unchecked")
    public StripedLruCache(int capacity, int stripes) {
        if (Integer.bitCount(stripes) != 1) throw new IllegalArgumentException("stripes must be a power of 2");
        segments = new Segment[stripes];
        int perSegment = Math.max(1, capacity / stripes);
        for (int i = 0; i < stripes; i++) {
            segments[i] = new Segment<>(perSegment);
        }
    }

    public V get(K key) {
        return segmentFor(key).get(key);
    }

    public void put(K key, V value) {
        segmentFor(key).put(key, value);
    }

    public int size() {
        int n = 0;
        for (Segment<K, V> s : segments) n += s.size(); // weakly consistent by design
        return n;
    }

    private Segment<K, V> segmentFor(K key) {
        int h = key.hashCode();
        h ^= (h >>> 16); // spread: high bits matter for the mask
        return segments[h & (segments.length - 1)];
    }

    private static final class Segment<K, V> {
        private final LinkedHashMap<K, V> map;

        Segment(int capacity) {
            this.map = new LinkedHashMap<>(capacity * 4 / 3 + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > capacity;
                }
            };
        }

        synchronized V get(K key) {
            return map.get(key); // mutates access order → must hold the segment lock
        }

        synchronized void put(K key, V value) {
            map.put(key, value);
        }

        synchronized int size() {
            return map.size();
        }
    }
}
