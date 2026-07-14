package com.agora.platform.snowflake;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeTest {

    @Test
    void idsAreUniqueAndSortedUnderConcurrency() throws InterruptedException {
        Snowflake snowflake = new Snowflake(7);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        int threads = 8, perThread = 50_000;

        List<Thread> workers = IntStream.range(0, threads)
                .mapToObj(t -> Thread.ofPlatform().unstarted(() -> {
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(ids.add(snowflake.next()), "duplicate id");
                    }
                }))
                .toList();
        workers.forEach(Thread::start);
        for (Thread w : workers) w.join(30_000);

        assertEquals(threads * perThread, ids.size());
    }

    @Test
    void laterIdsCompareGreater() throws InterruptedException {
        Snowflake snowflake = new Snowflake(1);
        long first = snowflake.next();
        Thread.sleep(2);
        assertTrue(snowflake.next() > first, "k-sortability violated");
    }

    @Test
    void base62RoundTripShape() {
        assertEquals("0", Base62.encode(0));
        assertEquals("z", Base62.encode(35));
        assertTrue(Base62.encode(Long.MAX_VALUE).length() <= 11);
    }
}
