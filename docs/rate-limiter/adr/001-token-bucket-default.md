# ADR-001: Token bucket default, Lua for atomicity

**Status:** accepted · **Date:** 2026-07-14

## Context
Distributed rate limiting needs a shared counter with atomic check-and-update. App-side
GET→compute→SET races under concurrency; WATCH/MULTI retries thrash under contention.

## Decision
Default algorithm: **token bucket** (lazy refill, state = 2 hash fields/key). Both algorithms
implemented as **Redis Lua scripts** — the script executes atomically inside Redis, so N
concurrent checks serialize without any client-side locking.

## Alternatives
- **Sliding-window log** (ZSET of timestamps): exact, but O(window·rate) memory per key — kept as
  the second implementation for comparison, selectable via `RL_ALGORITHM`.
- **Fixed window**: 2× burst at window edges — rejected as default, discussed in design doc.
- **App-side with WATCH/MULTI**: optimistic retries collapse under hot keys.

## Consequences
Token bucket tolerates bursts (that's a feature: burst=100 absorbed, then steady 50/s — exactly
the 123/177 measured split). Memory O(1) per key. Clock lives on Redis (Lua `redis.call('TIME')`
avoided in favor of caller ms for testability with injectable clocks).
