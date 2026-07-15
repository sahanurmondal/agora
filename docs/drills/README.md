# Interview Drill Pack

Every classic interview problem below has a **running implementation in this repo** — drill it
fresh on a whiteboard (35 min: 5 req / 5 estimation / 10 HLD / 10 deep-dive / 5 failure modes),
then compare against what's actually built and log gaps in DRILL-LOG.md.

| # | Interview problem | Live demo in this repo | Numbers you can cite |
|---|---|---|---|
| 1 | Rate limiter | `rate-limiter` (Go, Redis+Lua) via gateway | 300-burst → 123/177 exact token-bucket split; fail-open on outage |
| 2 | URL shortener | `link-service` | 500 rps @ p95 2.2ms; Snowflake+base62 = zero collisions |
| 3 | Unique ID generator | `platform-snowflake` + link/order IDs | 400k IDs, 8 threads, 0 duplicates, k-sortable |
| 4 | Design a KV / caching layer | catalog 3-tier read path | FLUSHDB under load → DB QPS bounded (single-flight) |
| 5 | News feed | `feed-service` | write: pushedTo=followers; read: pushedTo=0 + merge; hybrid threshold |
| 6 | Chat system | `chat-service` WS + Redis pub/sub | externalized state; presence TTL 30s |
| 7 | Notification system | `notification-service` | retry→DLT→redrive; SETNX dedup on replay |
| 8 | Autocomplete | search-service prefix ZSETs | O(log N + k) suggest, no search engine on the hot path |
| 9 | Search (secondary index) | outbox→Debezium→OpenSearch | searchable <2s after commit; idempotent PUT |
| 10 | Reservation / hotel booking | `inventory-service` | 1000 buyers / 100 stock → exactly 100 (optimistic); pessimistic side-by-side |
| 11 | Payment system | order saga + `payment-ledger` | kill-mid-saga → compensation trace; Idempotency-Key replay, no double charge |
| 12 | Digital wallet / ledger | `payment-ledger-service` | projection == fold(events); double-entry zero-sum |
| 13 | Proximity / nearby | `location-service` Redis GEO | 62m/636m results in 2.3ms, far courier excluded |
| 14 | Ad-click aggregation | `analytics-stream` | event-time windows, watermark seals, HLL uniques=8, late-merge counter |
| 15 | Metrics/observability | otel-lgtm + traces everywhere | one trace across gateway→services→Kafka consumer |
| 16 | Deployment/K8s story | k3d + kustomize + patterns | pod-kill under load: 200/200 OK; StatefulSet identity; canary/blue-green manifests |

Estimation anchors live in `load/NUMBERS.md` — quote MEASURED ceilings, not folklore.
