# Design Overview — concept cards per service

One card per service: the problem it embodies, the load-bearing decision, and the failure mode
you should be able to whiteboard. Full template docs exist for link-service, rate-limiter,
identity, edge-gateway (docs/<svc>/design.md); these cards cover the rest at drill depth.

## catalog-service — the caching masterclass
Read path L1 Caffeine (1s) → L2 Redis (5m + 10% jitter) → Postgres, with single-flight
coalescing (N concurrent misses per key = 1 DB query). **Decision:** jitter + coalescing over
"just cache it" — the interview is the failure modes: stampede (coalescing), synchronized expiry
(jitter), hot key (L1). Writes: transactional outbox in the same tx (dual-write problem). Media:
presigned PUT direct to MinIO — bytes never transit the service.

## search-service — eventual consistency made honest
Consumes the Debezium topic; indexing is a PUT keyed by product id = **naturally idempotent
consumer** (replay converges). Autocomplete: per-prefix Redis ZSETs — O(log N + k) suggestions
without touching the search engine. **Whiteboard trap:** index lag is a FEATURE to reason about
(read-your-writes doesn't hold across the CDC hop), not a bug.

## inventory-service — the no-oversell invariant
Optimistic = one conditional `UPDATE ... WHERE stock >= qty` (atomic, lock-free, losers see 0
rows); pessimistic = `SELECT FOR UPDATE` (serializes, predictable under fierce contention). Both
exposed for comparison. Reservation PK = order id → retried reserves can't double-decrement.
**Proven: 1000 concurrent / 100 stock → exactly 100.**

## order-service — orchestration saga
Owns the state machine: reserve → pay → confirm; compensation (release) on payment failure;
every step in `saga_steps` (DB twin of the trace). Payment hop gets the full resilience stack:
2s timeout, CB (10-window/50%), one jittered retry — safe because payments dedup by order id.
**Whiteboard trap:** compensation failure → manual-redrive queue; sagas trade atomicity for
availability and pay in compensation logic.

## payment-ledger-service — event sourcing that can prove itself
Append-only `ledger_events`; every payment = two rows summing to zero (double entry); `balances`
is a same-tx CQRS projection; `payments` PK = the exactly-once dedup gate. `/ledger/rebuild`
recomputes balances from the log and diffs the projection — **the ES demo most candidates only
talk about.**

## chat-service — externalized connection state
WS frames publish to Redis pub/sub; every node delivers to local sockets only. Kill a node →
clients reconnect anywhere, nothing but the socket is lost (**proven live, 2-node test**).
Presence = TTL key refreshed per frame. **Trade-off vs sticky sessions:** no LB affinity needed,
but every message pays a Redis hop.

## notification-service — at-least-once, tamed
Retry w/ backoff → DLT (partition moves on: head-of-line poison unblocked, **proven**), redrive
endpoint, SETNX dedup released-on-failure (**the found-live lesson: an idempotency claim taken
before processing and kept on error turns retries into no-ops — never mark processed until
processing succeeded**). SSE for push, long-poll fallback.

## feed-service — the fan-out debate, runnable
Write path: push post into every follower list (reads O(1), celebrity writes explode). Read
path: store once, k-way merge at read. Hybrid: threshold flips per seller (**proven: pushedTo=2
vs pushedTo=0+merge**). **Numbers to quote:** fan-out cost = followers × write; read cost =
followed-celebs × merge.

## location-service — geo without a GIS
Redis GEO = geohash-in-a-ZSET (52-bit score); GEOSEARCH walks neighbor cells then filters by true
distance. **Proven: 62m/636m hits, far courier excluded, 2.3ms.** Courier TTL key = liveness.

## analytics-stream — streaming semantics in 150 lines
Event-time tumbling windows; watermark = max_event_ts − 5s seals windows; allowed lateness 30s
merges stragglers (counted), beyond that dropped (counted) — the lambda-reconciliation story.
HLL uniques (12KB per counter regardless of cardinality), ZINCRBY leaderboard.

## web-bff — API composition
One GraphQL query fans out to catalog+inventory+orders; partial degradation (page renders
without stock if inventory is down). **Trap:** N+1 on list fields → DataLoader batching is the
answer; this resolver keeps single-entity shape deliberately.
