# link-service — Design Doc

## 1. Problem & requirements
- Functional: create short link for an absolute http(s) URL; redirect `GET /{code}` to target; metadata lookup.
- Non-functional: read-heavy (~100:1), redirect p95 < 50 ms local, no duplicate codes ever.
- Out of scope (this phase): custom aliases, expiry, auth (arrives with edge-gateway), click events (Phase 6).

## 2. Estimation
| Metric | Predicted | Measured reality |
|---|---|---|
| Read QPS | 500 sustained target (local) | 500 rps peak sustained, p95 2.2 ms, p99 <200 ms, 0 failures (2026-07-14) |
| Write QPS | ~10% of reads | ~50 rps within same run, no degradation |
| Storage | 100M links × ~120 B ≈ 12 GB — single Postgres for years | n/a local |

## 3. API
- `POST /api/v1/links` `{url}` → `201 {code, short_url}` — (idempotency-key header lands with platform-idempotency)
- `GET /{code}` → `302 Location: target` — see ADR-001 for 302-vs-301
- `GET /api/v1/links/{code}` → `200 {code, url, cache_hit}`
- `GET /healthz`

## 4. Data model
`links(id BIGINT PK, code TEXT UNIQUE, url TEXT, created_at)` — `id` is a
Snowflake (41-bit ms | 10-bit machine | 12-bit seq), so the PK is k-sortable /
append-friendly. `code = base62(id)`: **no hash → no collisions by
construction**; uniqueness needs no retry loop. Trade-off vs hashing: codes are
enumerable (fine here; would add a keyed permutation if links were secrets).

## 5. High-level design
`client → link-service (Go) → Redis (cache-aside, TTL 1h + 10% jitter) → Postgres`
OTel traces → otel-lgtm; spans tag `cache.hit` for hit-ratio inspection in Tempo.

## 6. Deep dives
1. **ID generation without coordination** — Snowflake per instance (machine-id
   from env); clock-backwards handled by spinning until wall clock catches up.
   Contrast: DB sequence (single point, hot), UUID (128-bit, not sortable, index bloat).
2. **Cache-aside correctness** — populate-after-read with jittered TTL; cache
   write failure never fails the read. Sharding to 2 logical DBs by
   `id % 2` is the planned Phase-1 extension (hash-based app-level sharding).

## 7. Failure modes
| What breaks | Blast radius | Mitigation | Proven by |
|---|---|---|---|
| Redis down | reads fall through to Postgres (slower, correct) | cache-aside degrades gracefully | stop agora-redis, watch p95 |
| Redis FLUSHALL | miss storm on Postgres | TTL jitter (full coalescing in catalog-service) | chaos 04 (Phase 2) |
| Postgres down | writes + cold reads fail | healthz flips, LB would eject | `docker stop agora-postgres` |
| Clock skew backwards | ID collision risk | generator refuses to move back | unit-level reasoning |

## 8. Trade-offs & rejected alternatives
- Base62-of-Snowflake over MD5-prefix: zero collision handling vs enumerable codes.
- 302 over 301 (ADR-001).
- Go over Java here: tiny static binary, instant start — the infra-flavored services stay Go.
