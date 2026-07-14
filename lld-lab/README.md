# LLD & Concurrency Lab

Plain Java 21 + JUnit 5 (+ JMH from ex. 3). No Spring — interviews test raw
`java.util.concurrent` fluency. One exercise/week, ~2-3h, concurrency first.
Benchmarks land in `NUMBERS.md`; re-drills logged in `../docs/drills/DRILL-LOG.md`.

## Exercise log

### 01 — Bounded blocking queue ✅ (two variants)

**The trap:** `if` instead of `while` around `wait()` — spurious wakeups and
stolen signals mean the condition can be false again by the time a woken thread
re-acquires the monitor. And with producers + consumers sharing one wait-set,
single `notify()` can wake the wrong side → both wait forever.

**The fix:** always `while (…) wait()`; `notifyAll()` on the intrinsic-lock
variant. The `ReentrantLock` variant uses two `Condition`s (notFull/notEmpty)
so `signal()` is safe *and* cheaper — each signal targets the only wait-set
that can make progress.

**Trade-offs:**
- `wait/notifyAll` — zero allocations, but O(waiters) wasted wake-recheck under contention
- `ReentrantLock` + 2 conditions — targeted wakeups, `lockInterruptibly`, fairness option; slightly more code
- Real code: use `ArrayBlockingQueue` — this exercise exists to explain *why* it works
- Claim-then-take counter in the stress test avoids poison pills for clean consumer shutdown

**Proof:** `mvn -pl lld-lab test` — 8 producers × 8 consumers × 800k items,
per-element delivery bitmap (no loss, no duplicates), block-when-full and
interruptibility checks, 60s timeout.

### 02 — Thread pool from scratch (next)
### 03 — Thread-safe LRU cache
### 04 — Rate limiter (token bucket + sliding window)
### 05 — Connection pool
### 06 — RW locking: synchronized vs RWLock vs StampedLock
### 07 — Deadlock demo + fix
