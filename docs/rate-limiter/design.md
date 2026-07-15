# rate-limiter — Design Doc

## 1. Problem & requirements
Protect every gateway route with per-key (user/IP) per-rule limits. Non-functional: check adds
<5ms p95; limiter outage must not take the product down (consumer fails open).

## 2. Estimation
| Metric | Predicted | Measured |
|---|---|---|
| Check latency | <5ms (1 Lua round-trip) | in-process test <1ms; burst demo end-to-end healthy |
| State/key | O(1) token bucket (2 fields) | vs O(rate·window) for the ZSET log variant |

## 3. API
gRPC `RateLimiter.Check(key, rule) → {allowed, remaining, retry_after_ms, algorithm}` — see `contracts/proto/ratelimit.proto`.

## 4. Data model
`rl:{rule}:{key}` Redis hash: `tokens`, `last_refill_ms` (bucket) · `rlw:{rule}:{key}` ZSET (window log variant).

## 5. High-level design
gateway GlobalFilter → (50ms deadline) → this service → Redis Lua. Gateway fails OPEN on
UNAVAILABLE/DEADLINE (edge-gateway ADR-001 side).

## 6. Deep dives
1. **Atomicity**: the entire read-refill-decide-write happens inside one Lua script.
2. **Burst semantics**: bucket capacity ≠ rate; measured 123 = 100 burst + refill during drain.

## 7. Failure modes
| Breaks | Blast radius | Mitigation | Proven |
|---|---|---|---|
| Redis down | limiter errors | gateway fail-open (availability > protection) | gateway test `limiterOutageFailsOpen` |
| Cold gRPC channel | first call exceeds 50ms deadline | acceptable one-off fail-open; warm-up option noted | observed live, logged in NUMBERS.md |
| Hot limit key | one Redis shard hot | key = user/IP so cardinality high; salting if needed | doc |

## 8. Rejected alternatives
Fixed window (edge bursts), app-side CAS (contention), in-gateway in-memory limiting (per-node ≠ global).
