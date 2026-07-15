# Chaos Catalog

Format per experiment: **Inject → Observe → Expect → Talking point.**
✅ = already demonstrated live (result recorded). 🔜 = scripted/documented, run on chaos day.
Toxiproxy upgrade (network-level toxics instead of process kills) is the Phase-7+ refinement.

| # | Experiment | Inject | Expect | Talking point | Status |
|---|---|---|---|---|---|
| 01 | `payment-latency-spike` | **Toxiproxy latency toxic 3000ms** on the payment hop (`chaos` profile, proxy :21089) | order's 2s timeout fires → retry → CB OPEN → fail-fast CANCELLED; toxic removed → half-open probe → CONFIRMED | timeout budgets; fail fast beats cascade | ✅ **run 2026-07-15: 8/8 CANCELLED under toxic, `CLOSED to OPEN` logged w/ trace id, heal → CONFIRMED** |
| 02 | `kafka-consumer-poison` | order with `X-User-Id: poison` → notification throws | 3 retries w/ backoff → DLT, partition unblocked; `POST /notifications/dlq/redrive` replays | head-of-line poison; DLQ + redrive. **Root cause of the earlier silent failure — a gem: the SETNX dedup claim fired BEFORE processing and was never released on error, so error-handler RETRIES looked like duplicates and no-oped; the poison "succeeded" and never reached the DLQ. Fix: release the idempotency claim on failure.** | ✅ **run 2026-07-15: DLT watermark 0→1, partition unblocked (SSE right after poison)** |
| 03 | `db-primary-kill` | `docker stop agora-postgres` 20s | writes 5xx, health DOWN, auto-recover on start; replica profile adds the stale-read demo | failover, replication lag, read-your-writes | 🔜 scripted |
| 04 | `redis-flush-thundering-herd` | `FLUSHDB` under k6 load | miss spike but DB QPS bounded: single-flight coalescing collapses N concurrent misses per key to 1 query; TTL jitter prevents synchronized re-expiry | cache stampede defenses | 🔜 scripted |
| 05 | `duplicate-delivery-replay` | rewind consumer group offsets | notif log: `duplicate delivery suppressed` (Redis SETNX on outbox event id); search: idempotent PUT by id converges | exactly-once = at-least-once + idempotency | ✅ dedup path proven in code+logs |
| 06 | `split-brain-lock` | pause lock holder past TTL (needs Redis lock w/ fencing counter — inventory uses DB row locks which are immune) | stale holder's write rejected by fencing token | why TTL locks alone are unsafe; DB conditional update sidesteps it | 🔜 doc + lab |
| 07 | `retry-storm` | disable jitter in order-service retry, 50% failures | request amplification visible in RED dashboard; jitter on → smooth | retry budgets, metastable failure | 🔜 config toggle |
| 08 | `outbox-crash` | kill order-service right after commit, before Debezium polls | event still published exactly once on restart (log tailing ≠ dual write) | the dual-write problem | ✅ implicitly: Debezium replayed full history on connector (re)start with zero loss |
| 09 | `saga-mid-flight-kill` | kill payment-ledger, place order | RESERVE OK / PAY FAILED / COMPENSATE_RELEASE OK / CANCELLED; stock restored | orchestration + compensating transactions | ✅ **run 2026-07-14: exact result observed** |
| 10 | `ws-node-kill` | kill 1 of 2 chat nodes | clients reconnect to survivor, room delivery continues (state in Redis, not node) | sticky sessions vs externalized state | ✅ **run 2026-07-15: cross-node delivery + post-kill delivery on survivor both PASS** (scripted websockets client) |
| 11 | `slow-object-store` | bandwidth cap on MinIO (toxiproxy) | multipart retries per-part; nginx serves stale on origin error (`proxy_cache_use_stale`) | chunking, partial-failure isolation | 🔜 toxiproxy phase |
| 12 | `hot-key` | k6 80% traffic on one product | Caffeine L1 (1s TTL) absorbs; Redis single-key QPS stays sane | hot partition: local cache, key salting | 🔜 k6 scenario |

## Already-proven invariants (from wave gates — cite these in interviews)

- **Flash sale**: 1000 concurrent buyers / 100 stock → exactly 100 confirmed (optimistic conditional UPDATE)
- **Pod kill under load** (k3d): 200/200 requests succeeded during `kubectl delete pod` (readiness + PDB + 2 replicas)
- **StatefulSet identity**: pod deleted → same name + PVC data back
- **Idempotency-Key replay**: same key twice → one order, `X-Idempotent-Replay: true`, no double charge
- **Ledger**: projection == fold(event log); double-entry zero-sum after 100+ payments and a refund path
- **Rate limiter**: 300-burst → 123 allowed (=burst+refill) + 177×429; limiter outage → fail-open (availability > protection)
