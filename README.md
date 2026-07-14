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

## Services

| Service | Lang | Status | Concepts |
|---|---|---|---|
| `link-service` | Go | **Phase 1** | Snowflake IDs, base62, cache-aside Redis, 301-vs-302 (ADR-001) |
| edge-gateway, rate-limiter, identity | Java/Go | Phase 1 wk2 | gateway, distributed rate limiting |
| catalog, search | Java | Phase 2 | caching patterns, outbox+CDC, CDN |
| inventory, order, payment-ledger | Java | Phase 3 | saga, locks+fencing, event sourcing |
| chat, notification, web-bff | Java | Phase 5 | WebSocket, DLQ/backpressure, GraphQL |
| feed, location, analytics-stream | Java/Go/Py | Phase 6 | fan-out, geohash, windowing |

## Verification harness

- `make load SCRIPT=<name>` — k6 scenarios with hard thresholds; results ledger in `load/NUMBERS.md`
- `make chaos EXP=<name>` — Toxiproxy experiments in `chaos/experiments/`
- `lld-lab/` — concurrency + machine-coding track (`mvn -pl lld-lab test`)
