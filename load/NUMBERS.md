# NUMBERS.md — measured-performance ledger

The back-of-envelope anchor table: interview estimates quote these *measured*
single-node ceilings, not folklore. One row per significant k6 run.

| Date | Endpoint / scenario | Max sustained | Latency @ that rate | Breaking point | Bottleneck observed |
|---|---|---|---|---|---|
| 2026-07-14 | link-service 90/10 redirect/create (cache-warm, local) | 500 req/s (test ceiling, not service ceiling) | p95 2.2 ms · p99 <200 ms · avg 1.2 ms · 0 failures (39.8k reqs) | not reached | none at 500 rps — Go+Redis path barely warm; push a higher-rate run to find the ceiling |
| 2026-07-14 | gateway→rate-limiter (gRPC+Redis Lua token bucket, rate=50 burst=100) | 300-req parallel burst | 123 allowed / 177×429 — exactly burst(100) + refill(~23) during window | by design | first-call fail-open observed: gRPC channel warm-up exceeded the 50ms deadline (cold-start budget lesson) |
| 2026-07-14 | flash sale: order saga (reserve→pay→confirm), optimistic conditional UPDATE | 1000 concurrent buyers (P=100), 100 stock | exactly 100×201 CONFIRMED + 900×409; stock 0, version 100; ledger zero-sum | invariant held | 4 sequential HTTP hops per order; saga p95 not yet measured under load — k6 saga-throughput scenario pending |
| 2026-07-14 | CDC pipeline: outbox commit → Debezium → Kafka → OpenSearch searchable | n/a | first-event lag < 2s after connector snapshot | — | JSON-with-schema envelope doubles payload size; Avro+SR is the fix (Phase 5+) |
| 2026-07-15 | canary: Traefik weighted 90/10 via TraefikService + IngressRoute | 60 sampled requests | **54 stable / 6 canary — exact weights**; weight→0 patch = instant yank (20/20 stable) | — | same image both tracks, X-Version from env: deploy ≠ release |
| 2026-07-15 | striped LRU (lld-lab ex03, 8 threads, 4k keyspace) | 14.6M ops/s (16 stripes) vs 8.2M (global lock) | 1.8× from striping | lock contention | get() is a write (recency) → RW-lock useless; why Caffeine → W-TinyLFU |
| 2026-07-15 | Toxiproxy 3s latency on payment hop | 8 orders | 8/8 CANCELLED via saga compensation; CB CLOSED→OPEN; heal → CONFIRMED | by design | network-level fault ≠ process kill: connection succeeds, response never comes — the nastier failure mode |
