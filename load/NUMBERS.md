# NUMBERS.md — measured-performance ledger

The back-of-envelope anchor table: interview estimates quote these *measured*
single-node ceilings, not folklore. One row per significant k6 run.

| Date | Endpoint / scenario | Max sustained | Latency @ that rate | Breaking point | Bottleneck observed |
|---|---|---|---|---|---|
| 2026-07-14 | link-service 90/10 redirect/create (cache-warm, local) | 500 req/s (test ceiling, not service ceiling) | p95 2.2 ms · p99 <200 ms · avg 1.2 ms · 0 failures (39.8k reqs) | not reached | none at 500 rps — Go+Redis path barely warm; push a higher-rate run to find the ceiling |
| 2026-07-14 | gateway→rate-limiter (gRPC+Redis Lua token bucket, rate=50 burst=100) | 300-req parallel burst | 123 allowed / 177×429 — exactly burst(100) + refill(~23) during window | by design | first-call fail-open observed: gRPC channel warm-up exceeded the 50ms deadline (cold-start budget lesson) |
