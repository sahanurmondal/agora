# link-service

URL shortener (Go). Exists to exercise: **Snowflake IDs** (coordination-free,
k-sortable), **base62 codes** (collision-free by construction), **cache-aside
Redis** with jittered TTL, and the **301-vs-302** trade-off (ADR-001 → 302,
keeps click analytics alive).

## Endpoints
- `POST /api/v1/links` `{"url": "https://…"}` → `201 {code, short_url}`
- `GET /{code}` → `302` to target
- `GET /api/v1/links/{code}` → metadata incl. `cache_hit`
- `GET /healthz`

## Config (env)
`LINK_PORT` (8081) · `LINK_DB_URL` (postgres://…localhost:5433/linkdb) ·
`LINK_REDIS_ADDR` (localhost:6380) · `LINK_MACHINE_ID` (0-1023) ·
`OTEL_EXPORTER_OTLP_ENDPOINT` (http://localhost:4318)

Host ports are 5433/6380 because a local Homebrew Postgres/Redis owns 5432/6379.

## Design doc & ADRs
`docs/link-service/design.md` · `docs/link-service/adr/001-302-over-301.md`

## Demo (≤5 commands)
```bash
make up PROFILE=core
cd services/link-service && go build -o bin/link ./cmd/link
set -a && source ../../.env && set +a && ./bin/link &
curl -s -X POST localhost:8081/api/v1/links -H 'Content-Type: application/json' -d '{"url":"https://example.com"}'
curl -i localhost:8081/<code-from-above>     # → 302; trace visible in Grafana (localhost:3000) → Tempo
```

Measured: 500 req/s @ p95 2.2 ms, 0 failures (`load/NUMBERS.md`).
