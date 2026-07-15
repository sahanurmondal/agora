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

### 02 — Thread pool from scratch ✅

**The trap:** the submit/shutdown race — a check-then-enqueue outside one monitor lets a task
land *behind* the poison pills: accepted but never executed. Fix: flag check AND enqueue under
the same lock (safe to block on a full queue while holding it — workers drain without that lock).
Second trap: a task's uncaught exception killing the worker; workers catch Throwable, only the
pill exits. **Proof:** 8 threads × 25k submissions with mid-stream shutdown →
`executed == accepted`, `accepted + rejected == attempted`; pool survives 10 throwing tasks.
### 03 — Thread-safe LRU cache ✅
**Trap:** `get()` mutates recency → it's a write; RW-lock buys nothing. **Fix:** lock striping —
16 segments, each `LinkedHashMap(accessOrder)` + lock. **Measured: striped 14.6M ops/s vs
global-lock 8.2M = 1.8× on 8 threads.** Trade-off: per-segment eviction, weakly-consistent size —
why Caffeine uses W-TinyLFU. (JMH-grade benchmark = refinement; current probe is directional.)

### 04 — Rate limiter (token bucket) ✅
**Traps:** read-refill-write must be atomic (one monitor here; Redis Lua in the flagship — same
algorithm, two scales); wall clock steps back under NTP → monotonic `nanoTime` via injectable
`LongSupplier` (deterministic tests: burst 5 drains, +300ms refills exactly 3, refill caps at burst).
32-thread test: admitted ≤ burst + rate·t.

### 05 — Connection pool ✅
**Traps:** factory throws after `acquire()` → permit leaks (try/release); double-release → one
conn pooled twice (borrowed-set rejects). Leak detector records borrower stack + hold time
(HikariCP's leakDetectionThreshold, hand-rolled). 16 threads × 200 borrows: in-use never exceeded
capacity 4, factory created ≤ 4.

### 06 — RW locking comparison 🔜
Folded partially into 03's striped-vs-global probe; full synchronized/RWLock/StampedLock JMH
matrix is the remaining refinement.

### 07 — Deadlock demo + fix ✅
Opposing naive transfers interlock (all four Coffman conditions); detected live via
`ThreadMXBean.findDeadlockedThreads()` on daemon threads. Fix shown: global lock ordering by
account id — 4k opposing ordered transfers complete, balances conserve.
