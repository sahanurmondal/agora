# Agora — System Design Mastery Lab

A local-commerce marketplace built as a hands-on lab covering the system-design
concepts expected of a staff engineer (Alex Xu Vol 1+2, System Design Primer,
microservices.io, DDIA) — every concept backed by running code, a k6 measurement,
or a chaos experiment.

**Plan**: see `docs/` for design docs, ADRs, capacity sheets, and drill logs.
**Concept-coverage matrix and 12-week roadmap**: `docs/PLAN.md`.

## Quick start

```bash
cp .env.example .env
make up PROFILE=core        # Postgres, Redis, Redpanda, MinIO, Grafana LGTM  (~3 GB RAM)
make smoke
```

| UI | URL |
|---|---|
| Grafana (traces/metrics/logs) | http://localhost:3000 |
| Redpanda Console | http://localhost:8085 |
| MinIO Console | http://localhost:9001 |

## Services (all 14 built & gate-tested)

| Service | Port | Lang | Concepts proven |
|---|---|---|---|
| `edge-gateway` | 8080 | Java/SCG | L7 routing, JWT edge validation, RL filter w/ 50ms deadline + **fail-open** |
| `link-service` | 8081 | Go | Snowflake+base62, cache-aside+jitter, 302 (ADR); **500rps @ p95 2.2ms** |
| `identity-service` | 8082 | Java | stateless JWT (HS256), BCrypt |
| `catalog-service` | 8083 | Java | **L1 Caffeine/L2 Redis/L3 PG + single-flight coalescing**, outbox→CDC, presigned media |
| `search-service` | 8084 | Java | CDC indexing (**searchable <2s**), idempotent PUT, prefix-ZSET autocomplete |
| `inventory-service` | 8087 | Java | optimistic vs pessimistic side-by-side; **1000 buyers/100 stock → exactly 100** |
| `order-service` | 8088 | Java | **saga + compensation (kill-tested)**, CB+retry+jitter, Idempotency-Key replay |
| `payment-ledger` | 8089 | Java | event sourcing, CQRS projection (**== fold(log)**), zero-sum double entry |
| `chat-service` | 8090 | Java | WebSocket + Redis pub/sub cross-node, presence TTL |
| `notification-service` | 8091 | Java | retry→**DLQ→redrive**, SETNX dedup, **SSE** + long-poll |
| `feed-service` | 8092 | Java | **fan-out write vs read vs hybrid** (flag-flippable, demo-proven) |
| `location-service` | 8093 | Go | Redis GEO proximity (**2.3ms nearby**) |
| `analytics-stream` | 8094 | Python | **event-time windows + watermarks**, HLL uniques, leaderboard |
| `web-bff` | 8095 | Java | GraphQL aggregation, partial degradation |
| `rate-limiter` | 9095 | Go | gRPC + Redis Lua token bucket/sliding window (**123/177 exact split**) |

**Kubernetes (k3d)**: kustomize base+overlays, probes/HPA/PDB, StatefulSet, Ingress —
**pod-kill under load: 200/200 OK**; rolling rollback demoed. `infra/k8s/deploy-patterns/` = canary (Traefik weights) + blue-green.
**Chaos**: `chaos/run.sh <exp>` — catalog in `chaos/experiments/CATALOG.md`.
**Drills**: `docs/drills/README.md` maps 16 interview problems → live demos + citable numbers.

## Verification harness

- `make load SCRIPT=<name>` — k6 scenarios with hard thresholds; results ledger in `load/NUMBERS.md`
- `make chaos EXP=<name>` — Toxiproxy experiments in `chaos/experiments/`
- `lld-lab/` — concurrency + machine-coding track (`mvn -pl lld-lab test`)
