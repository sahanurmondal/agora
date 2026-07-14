# System Design Mastery Lab — Flagship Hands-On Project Plan

## Context

The user (Java/Spring + Kafka backend engineer, macOS Apple Silicon, local-first preference) wants a hands-on project covering **80-90% of system design concepts** expected of a staff engineer in MAANG interviews — the material of Alex Xu's System Design Interview Vol 1+2, donnemartin/system-design-primer, microservices.io's pattern catalog, and DDIA-level distributed theory. The deliverable is working code, not notes: microservices patterns, multiple protocols, caching, resilience, Docker + Kubernetes deployment, observability, load testing, and chaos experiments.

**User decisions (via AskUserQuestion):**
- Stack: **Polyglot** — Java/Spring Boot core, Go/Python where apt
- Format: **One flagship mono-repo** (10-15 services, shared infra, cumulative learning)
- Depth: **Architecture-first with real infra** (Redis/Kafka/Postgres/MinIO...), not build-your-own-internals
- Pace: **10-15 hrs/week → ~10-12 week roadmap**

## Existing assets audit (/Users/sahanur/IdeaProjects)

| Asset | State | Disposition |
|---|---|---|
| `log-analytics-platform` | REAL: Maven multi-module, Spring Cloud Gateway + config-server, Kafka+Avro+Schema Registry, Elasticsearch+Kibana, Zipkin, GraphQL; working docker-compose | Best foundation; cluttered with DSA files |
| `real-time-notification-service` | Partial: Kafka fan-out (ingestion-api → worker → email/SMS/push) | Port into flagship |
| `MyChatApp` | Working WebSocket chat (Spring Boot + React/TS, presence, msg status) | Port WebSocket core into flagship |
| `ride-matching-platform` | Scaffold w/ real Rider/Driver/Trip entities, stub services | Optional geo-service head start |
| 29 other `*-platform` dirs | Empty 4-file batch scaffolds (Aug 2025), no Docker/K8s | Dead; keep BLUEPRINT.md docs only |
| `sd code/` | 19 cloned reference repos (not user's work) | Study material only |

**Gaps found:** zero Kubernetes anywhere, Redis never actually used, no gRPC in own code, no rate limiting / consistent hashing / geo / caching implementations.

## Research summary (3 agents)

### Concept checklist (must-cover, highest-frequency first)
1. L4/L7 load balancing, stateless scaling, service discovery
2. Caching: cache-aside / write-through / write-behind, LRU/TTL, thundering herd, hot keys
3. Consistent hashing (+ virtual nodes)
4. DB sharding + replication (read replicas), denormalization
5. CAP/PACELC, eventual vs strong consistency, quorums
6. Message queues: partitions, consumer groups, backpressure, DLQ, delivery semantics
7. CDN + object storage (multipart, presigned URLs)
8. Rate limiting: token bucket, sliding window, Redis+Lua distributed
9. WebSocket vs SSE vs long-poll, presence, fan-out
10. Capacity estimation (practiced via k6 load numbers)
11. Microservices patterns: API gateway/BFF, database-per-service, saga (orch+choreo), CQRS, event sourcing, transactional outbox + CDC (Debezium), idempotent consumer, idempotency keys, circuit breaker/retry+jitter/bulkhead/timeout (resilience4j), strangler fig, service mesh/sidecar, health checks, distributed tracing, log aggregation, blue-green/canary, feature flags, contract tests
12. Distributed theory hands-on: distributed locks + fencing, optimistic vs pessimistic concurrency, exactly-once (dedup+idempotency), stream windowing/watermarks, geohashing, fan-out-on-write vs read, Redis sorted sets, trie/autocomplete, Snowflake IDs, bloom filter, HyperLogLog, time-series metrics

### Toolchain (local-first, MacBook RAM-aware)
- Docker Compose **profiles** per subsystem → **k3d** for the K8s phase (lightest on Apple Silicon); Tilt for inner loop
- **Kafka** (user already knows it; Redpanda capped at 1G as fallback), Redis, Postgres (+ read replica), MinIO (S3), OpenSearch only for search service
- Observability: OpenTelemetry SDKs → **grafana/otel-lgtm** all-in-one (Prometheus+Tempo+Loki+Grafana)
- **k6** load testing, **Toxiproxy** chaos, **Debezium** for outbox/CDC, Linkerd if mesh (not Istio — too heavy locally)

---

## Architecture: AGORA — flagship system-design mono-repo

### Flagship domain: "Agora" — a local-commerce marketplace with real-time features

A marketplace (sellers, buyers, products, flash sales, couriers) *natively* needs every reference problem: checkout saga + payment/wallet ledger, inventory reservation (hotel-booking pattern), buyer↔seller chat (absorbs MyChatApp), notifications (absorbs real-time-notification-service), seller-follow news feed, search + autocomplete, courier proximity/geo, promoted-listing click aggregation (ad-click), trending leaderboard, share-links (URL shortener), product images (S3/CDN), gateway + rate limiter at the edge. The existing `*-platform` BLUEPRINT.md docs (marketplace, social-feed, search-autocomplete, payment-orchestration, gaming-leaderboard) become raw material for design docs.

**New repo at `/Users/sahanur/IdeaProjects/agora`** — do not build inside existing scaffolds; they stay as harvest sources.

### Repo layout

```
agora/
├── README.md                  # portfolio front page: arch diagram, demo index
├── Makefile                   # up-<profile>, smoke, load-<svc>, chaos-<exp>, k3d-up
├── Tiltfile                   # introduced Phase 4
├── docker-compose.yml         # profiles: core|search|cdc|media|obs|legacy|chaos|quorum
├── pom.xml                    # Java parent reactor (Java 21, Boot 3.3.x)
├── go.work                    # Go workspace: rate-limiter, link-service, location-service
├── contracts/                 # proto/, avro/, openapi/, pact/
├── platform/                  # shared Java libs: platform-otel, platform-outbox,
│                              # platform-idempotency, platform-snowflake
├── services/                  # 15 services (map below)
├── infra/
│   ├── compose/               # postgres init SQL (db-per-service), replica setup,
│   │   └── nginx-cdn/         # redis.conf, nginx cache in front of MinIO
│   ├── debezium/              # connector JSON (order-outbox, catalog-outbox)
│   ├── k8s/                   # k3d-config.yaml + kustomize base/ + overlays/ (see K8s section)
│   ├── observability/dashboards/  # provisioned Grafana JSON
│   └── flagd/flags.json       # feature flags (feed fan-out strategy, RL algo)
├── docs/                      # design/, adr/, capacity/, drills/, templates/
├── load/                      # k6 scenarios + NUMBERS.md (see harness section)
├── chaos/                     # toxiproxy scenarios + experiments/ (see harness section)
└── lld-lab/                   # LLD & concurrency track (section pending)
```

Build choices: **Maven parent reactor** for Java (mirrors log-analytics-platform the user knows; notification-service modules re-parent cheaply), `go.work` for Go, `uv` for Python. **Redpanda over Kafka** — capped `--memory=1G --smp=1`, built-in Schema Registry + Console replaces 3 Confluent containers (~2 GB saved); 100% Kafka API so all Spring Kafka knowledge transfers unchanged → **ADR-001**. (This lowers the K8s-section RAM budget by ~0.5-1 GB.)

### Service map (15 services)

| # | Service | Lang | Protocols | Datastores | Concept cluster |
|---|---------|------|-----------|------------|-----------------|
| 1 | `edge-gateway` | Java (Spring Cloud Gateway, lifted from log-analytics) | HTTP in, REST/gRPC out | — | API gateway, L7 routing, JWT validation, strangler-fig routing, timeouts |
| 2 | `rate-limiter` | **Go** | gRPC (gateway filter calls it) | Redis (Lua) | Token bucket + sliding window (atomic Lua), distributed RL, gRPC, algo toggle via flagd |
| 3 | `identity-service` | Java (harvest MyChatApp auth) | REST | Postgres | JWT/authN, stateless sessions |
| 4 | `catalog-service` | Java | REST + gRPC; presigned-URL media | Postgres (**+read replica**), Redis, MinIO | Cache-aside + TTL jitter, thundering herd (coalescing), hot key (Caffeine L1 + Redis), replica routing, **outbox → Debezium CDC**, object storage + CDN (nginx) |
| 5 | `search-service` | Java | REST | OpenSearch, Redis | CDC-driven indexing, autocomplete (trie / ZRANGEBYLEX + top-k), eventual consistency, idempotent consumer |
| 6 | `inventory-service` | Java | gRPC + REST | Postgres, Redis | **Distributed lock + fencing token**, optimistic (@Version) vs pessimistic (SELECT FOR UPDATE) side-by-side, reservation-with-TTL, no-oversell invariant |
| 7 | `order-service` | Java | REST | Postgres | **Saga orchestration** + compensation, outbox+CDC, idempotency keys, resilience4j (CB/retry+jitter/timeout/bulkhead), Snowflake IDs |
| 8 | `payment-ledger-service` | Java | gRPC | Postgres | **Event sourcing** (double-entry ledger), **CQRS** projection, exactly-once (dedup + idempotent consumer), choreographed refund saga |
| 9 | `chat-service` | Java (**port of MyChatApp**) | WebSocket + REST | Postgres, Redis pub/sub | WS at scale, presence, cross-node delivery, **consistent hashing** for session ownership |
| 10 | `notification-service` | Java (**absorbs real-time-notification-service**) | Kafka consumer + **SSE** + long-poll | Postgres, Redis | Fan-out, **backpressure** (bounded queues, pause/resume), **DLQ + redrive**, WS-vs-SSE-vs-long-poll comparison |
| 11 | `feed-service` | Java | REST + SSE | Redis (lists/zsets), Postgres | **Fan-out-on-write vs read** (hybrid, celebrity threshold behind flagd), write-through cache |
| 12 | `location-service` | **Go** | gRPC streaming in, WS out | Redis GEO | **Geohashing**/proximity, streaming ingest |
| 13 | `link-service` | **Go** | REST | Postgres (**2 logical shards**), Redis | URL shortener: Snowflake, base62, cache-aside, **bloom filter**, app-level sharding, 301-vs-302 ADR |
| 14 | `analytics-stream` | **Python** (confluent-kafka + FastAPI) | Kafka consumer, REST | Redis (zsets, **HLL**), Postgres | **Windowing + watermarks**, late events, **write-behind** flush, HLL uniques, **leaderboard** |
| 15 | `web-bff` | Java (Spring GraphQL, pattern from log-analytics query-api) | **GraphQL** | — | BFF, N+1 handling, **Pact contract tests** vs catalog/order/feed |

Language budget: Java 10 (interview center of gravity), Go 3 (infra-flavored), Python 1 (stream). Every service: db-per-service (separate DBs in one Postgres), health endpoints, OTel, Dockerfile, kustomize base.

### Concept-coverage matrix (concept → where → proof)

| Concept | Where | Verified by |
|---|---|---|
| L4/L7 LB, stateless scaling | edge-gateway; k8s Service vs Ingress | scale catalog ×3 under k6, flat p99 |
| Cache-aside / write-through / write-behind / LRU | catalog+link / feed / analytics / Redis allkeys-lru | hotkey k6 cache-off vs on; hit-ratio panel |
| Thundering herd / hot key | catalog (coalescing, jitter), link (L1) | FLUSHDB under load → DB QPS bounded |
| Consistent hashing | chat session ring (platform lib) | kill 1/3 chat pods → only ~1/3 reconnect |
| Sharding + read replicas | link (2 shards); catalog replica routing | shard-distribution metric; lag demo via Toxiproxy |
| CAP / eventual vs strong | inventory (strong) vs search/feed (eventual) | write product, race the index; stale-read demo |
| Queues, backpressure, DLQ | notification-service | 10× burst drains, zero loss; poison → DLQ → redrive |
| CDN + object storage | MinIO + nginx cache | `X-Cache: HIT`; image p99 |
| Quorum | Redpanda 3-node `quorum` profile, acks=all, min.insync=2 | kill 1 broker fine; kill 2 → writes block |
| Rate limiting | rate-limiter (Go) | exact 429 ratio; flagd flips algorithm live |
| WS / SSE / long-poll | chat / notification+feed / fallback | 2k sockets k6; browser SSE demo |
| Saga (orch + choreo) | order checkout / refund flow | **kill payment mid-saga → compensation in one trace** |
| CQRS + event sourcing | payment-ledger | replay events → projection matches |
| Outbox + CDC | order, catalog (Debezium) | stop Debezium 60s under writes → zero loss |
| Idempotency keys + consumer | order/payment; all consumers | replay key 100× → 1 order |
| CB / retry+jitter / bulkhead | order-service (resilience4j) | Toxiproxy latency → CB opens, no thread exhaustion |
| Strangler fig | gateway routes legacy MyChatApp → chat-service | `legacy` profile, route-by-route flip |
| Blue-green / canary / rolling / flags | k8s section mechanisms | see K8s section demos |
| Locks + fencing, optimistic vs pessimistic | inventory | flash-sale k6: 1000 buyers, 100 stock → exactly 100 |
| Exactly-once | payment consumer | duplicate-delivery chaos, balance unchanged |
| Windowing/watermarks, HLL, leaderboard | analytics-stream | late events → correct windows; HLL vs exact; trending API |
| Geohash / Snowflake / bloom | location / link / link | nearby <10ms; k-sortability; measured FP rate |
| Capacity estimation | docs/capacity/ + NUMBERS.md | every design doc + drill pack |

**Flagged gaps (patch via side-lab or doc):** wide-column hands-on (optional 2h ScyllaDB-capped lab: chat-history table); Raft/consensus internals (doc + k3d's embedded etcd); multi-region (doc only — not local-feasible).

### Phased roadmap (12 weeks @ 10-15 h/wk — each phase ends stoppable-with-coherent-portfolio)

| Phase | Weeks | Build | Exit demo (gate) |
|---|---|---|---|
| **1. Edge slice** | 1-2 | Repo bootstrap, `core`+`obs` profiles; `link-service` (Go, instant win), `identity-service` (harvest MyChatApp auth), `edge-gateway`; then `rate-limiter` (Go gRPC + Redis Lua) as gateway filter | JWT login → short link through gateway; k6 500 rps p99<50ms; exact 429 behavior; full trace gateway→RL→link in Grafana |
| **2. Catalog, search, media** | 3-4 | `catalog-service` (+replica, outbox), `cdc` profile (Debezium), `search` profile (OpenSearch), `search-service`, MinIO + nginx-cdn | product → searchable ≤2s via CDC; autocomplete <10ms; hot-key k6; CDN cache HIT; stale-read-on-lagged-replica demo |
| **3. Transactional core** | 5-6 | `inventory-service`, `order-service` (saga + resilience4j), `payment-ledger-service` (ES + CQRS) | **Flash sale: 1000 buyers/100 stock → exactly 100 orders, ledger zeroes; kill payment mid-saga → compensation in one Tempo trace**; idempotency replay; CB-open |
| **4. k3d migration** | 7 | Cluster + kustomize for all built services (stateful infra stays in compose via `host.k3d.internal` — ADR), probes, limits, HPA, Tiltfile | `make k3d-up` all green; pod kill under k6 → no failed requests; zero-downtime rolling update |
| **5. Realtime subsystem** | 8-9 | Port `chat-service` (WS + hash ring), absorb `notification-service` (DLQ, backpressure, SSE), `web-bff` (GraphQL + Pact); strangler-fig `legacy` profile | 2-node chat, 2k sockets, node kill → 1/N reconnect; poison→DLQ→redrive; order event → SSE toast; GraphQL fan-out in one trace; strangler flip |
| **6. Feed, geo, analytics** | 10-11 | `feed-service` (hybrid fan-out behind flagd), `location-service`, `analytics-stream` | celebrity fan-out-on-read vs normal on-write, flag flip live; nearby <10ms; click storm → correct windows incl. late events; trending API |
| **7. Deploy patterns + chaos day + drills** | 12 | Canary (Traefik weighted; Argo Rollouts auto-rollback = stretch), blue-green (selector flip), flagd kill-switch, `quorum` broker-kill, **full 12-experiment chaos day**, finish `docs/drills/` (14 reference problems → 45-min outlines pointing at live demos); Linkerd = optional stretch (skip on 16GB) | canary rollback recorded; chaos log complete; drill pack done |

RAM discipline: never run `search`+`quorum`+`legacy` together; Makefile encodes valid combos (core+obs ≈ 3.5 GB; +search/cdc ≈ 6 GB; k3d apps ≈ 4 GB with infra in compose).

### Reuse strategy (cheapest path)

- **MyChatApp**: copy `JwtUtil`, `SecurityConfig`, `User/UserRepository` → `identity-service` (Wk 1); `WebSocketConfig` + chat controllers → `chat-service` (Wk 8) then add Redis pub/sub + hash ring. Original stays untouched under `legacy` profile as the strangler-fig target; its React UI reusable as demo front-end.
- **real-time-notification-service**: copy `core-domain`, `ingestion-api`, `notification-worker` → `services/notification-service` (single Boot app, worker via Spring profile), re-parent to agora pom. ~2-3h migration, not a rewrite.
- **log-analytics-platform**: **keep separate as-is** (already a complete portfolio piece; its ES 8.11 + Zipkin conflicts with agora's OpenSearch + Tempo). Harvest only: gateway config, Avro wiring, GraphQL patterns. (Separate 1h cleanup of stray DSA files — not on critical path.)
- **ride-matching-platform + 29 scaffolds**: code dead, do not port. Mine BLUEPRINT.md files as design-doc first drafts.

---

## Repo bootstrap & build tooling

### Java build — Maven parent reactor

Maven over Gradle: every real project of the user's (log-analytics, notification-service, MyChatApp) is Maven, and the reuse plan re-parents those modules directly; one less new tool in an already-dense plan. Root `pom.xml` (packaging `pom`): modules = `platform/*` + Java `services/*` + `lld-lab`; `dependencyManagement` imports Boot 3.3.x + Spring Cloud BOMs; versions pinned in properties. **Jib maven plugin** in `pluginManagement` → `${REGISTRY:-localhost:5001}/<svc>` (no Dockerfiles for JVM). Integration tests via failsafe (`*IT`) + Testcontainers singletons from `platform-testing`. Spring Cloud deps only in edge-gateway; business services stay plain Boot.

### Go + Python + orchestration

- **Go**: each `services/<go-svc>/` own module; root `go.work` ties them for IDE/build. Multi-stage Dockerfile → distroless.
- **Python**: `uv` per service (`pyproject.toml` + `uv.lock`), `python:3.12-slim` multi-stage.
- **Makefile** (over justfile — preinstalled, zero-dependency), dispatch by marker file (`pom.xml`/`go.mod`/`pyproject.toml`):

```
make up PROFILE=core|search|cdc|media|legacy|chaos|quorum|all
make build SVC=<svc>       # jib | go build | docker build
make test [SVC=<svc>]      # mvn test + go test ./... + uv run pytest
make itest                 # failsafe + Testcontainers
make smoke                 # health checks through the gateway
make load SCRIPT=<name>    # k6 run load/scenarios/<name>.js
make chaos EXP=<name>      # chaos/run.sh <name>
make k8s-up|k8s-down|tilt
```

### Shared libs (`platform/`, Java only)

`platform-otel` (OTel setup, every Java service) · `platform-idempotency` (Idempotency-Key filter + store) · `platform-outbox` (outbox entity + transactional writer) · `platform-snowflake` (ID generator) · `platform-testing` (Testcontainers singleton bases, seeders). **Deliberately NOT shared:** domain models/DTOs — the wire is defined by `contracts/` (proto/Avro/OpenAPI), never a shared jar. Shared-libs-vs-coupling trade-off recorded as an ADR. Go/Python stay dependency-independent (copy the few helpers needed — independence *is* the microservices lesson).

### Compose conventions

Single `docker-compose.yml` with `profiles:`; containers `agora-<thing>`; every infra container has a `healthcheck`, every app uses `depends_on: condition: service_healthy`. `core` = Postgres (+init SQL, db-per-service), Redis, **Redpanda (capped 1G, built-in Schema Registry + Console)**, MinIO, grafana/otel-lgtm (~3.5 GB). Add-ons: `replica` (+150 MB), `search` OpenSearch 512m heap (+1.5 GB), `cdc` Connect+Debezium (+1 GB), `media` nginx-cdn, `legacy` (old MyChatApp), `chaos` (Toxiproxy), `quorum` (3-node Redpanda). Env: `.env.example` committed with pinned versions/ports/creds; real `.env` gitignored (user's existing convention).

### Testing & CI

- Unit (`mvn test`, no containers) / integration (failsafe + Testcontainers) split; `make smoke` through the gateway.
- **Contract tests: Pact, broker-less** (over Spring Cloud Contract — consumers are polyglot; mono-repo needs no broker): consumers write pact JSON to `contracts/pacts/` (committed), producers verify from the local dir. Kafka contracts = Avro in `contracts/avro/` + Schema Registry `BACKWARD` compatibility checked at build.
- **CI (`.github/workflows/ci.yml`, private repo)**: `dorny/paths-filter` → matrix of affected services only; per-toolchain build+test; Testcontainers itest job; images to GHCR on main (SHA-tagged). No deploy stage — k3d is local-only.
- **Conventions (`docs/CONVENTIONS.md`)**: Conventional Commits scoped by service (`feat(<svc>): ...`); ADRs `docs/adr/NNN-*.md` (repo-wide) + `docs/<service>/adr/` (per-service); trunk-based with short-lived `feat/<svc>-<topic>` branches, squash merge; per-service README ends with the ≤5-command demo.

---

## LLD & concurrency lab — `lld-lab/`

Standalone Maven module at repo root: **plain Java 21 + JUnit 5 + JMH, no Spring** (interviews test raw `java.util.concurrent` fluency without framework crutches; co-locating shares one toolchain, CI, and the NUMBERS/drill-log conventions). Layout: `lld-lab/src/main/java/lab/{concurrency,machinecoding}/`, one package + README per exercise.

### Concurrency set (build in order — strongest staff signal)

| # | Build | Key trap | Proving test |
|---|---|---|---|
| 1 | **Bounded blocking queue** ×2: `wait/notifyAll`, then `ReentrantLock` + 2 `Condition`s | `if` instead of `while` around `wait()`; single `notify()` losing wakeups | 8P×8C×1M items: consumed checksum == produced, within timeout |
| 2 | **Thread pool from scratch** (workers on build #1, submit/shutdown vs shutdownNow, saturation rejection) | Shutdown race (submitted task executed *or* rejected, never dropped); dead worker replacement | 100k tasks + mid-stream shutdown: `executed+rejected == submitted` |
| 3 | **Thread-safe LRU cache** (single lock → lock-striped) | `get()` mutates recency = it's a write, so RW-lock doesn't help | invariant test + JMH: global vs striped vs CHM-approx; talking point: Caffeine's W-TinyLFU |
| 4 | **Rate limiter**: token bucket (lazy refill, nanoTime) + sliding-window counter | read-refill-write race → CAS; fixed-window 2× edge burst | injectable `Clock` determinism; 32-thread acquires ≤ limit. *Redis+Lua distributed version lives in the flagship gateway — same algorithm, externalized state* |
| 5 | **Connection pool** (`Semaphore` + `BlockingQueue`, acquire timeout, leak detector w/ borrower stack) | permit leak when creation throws post-acquire; double-release | borrow-storm invariant; deliberate leak → detector names borrower |
| 6 | **RW locking in anger**: `synchronized` vs `ReentrantReadWriteLock` vs `StampedLock` optimistic | read→write upgrade self-deadlock; writer starvation | JMH 95/5 mix across all three, record winners |
| 7 | **Deadlock demo + fix** (bidirectional transfers) | the deadlock is the exercise | detected via `ThreadMXBean.findDeadlockedThreads()`; fixed via lock ordering AND tryLock+backoff |

Benchmark results → `lld-lab/NUMBERS.md` (same ledger convention).

### Machine-coding set (90-min timebox each; log what got cut)

| Problem | Patterns | Concurrency angle |
|---|---|---|
| Parking lot | Strategy (pricing/allocation), Factory | atomic spot claim under racing entries |
| **BookMyShow seat booking** | State (AVAILABLE→HELD→BOOKED w/ TTL), Strategy, Builder | per-seat pessimistic vs optimistic version-check — **deliberately the flagship's reservation problem at process scale** (flagship re-solves with DB versioning + distributed locks + fencing, chaos exp 06); same invariant, two scales = the staff answer |
| Splitwise | Strategy (splits), Observer, min-cash-flow | none — modeling drill |
| Logging framework | Decorator (formatters), Observer (appenders), Builder | async appender reuses builds #1-2 |
| Vending machine | State (canonical state machine), Factory | none |

### Pattern usage map — learned in situ in the flagship

Strategy → RL algorithm, fan-out toggle, pricing · State → order/payment lifecycle, CB states, saga steps · Observer → `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` feeding the outbox · Decorator → middleware chains · Builder → request/config + Avro/proto construction · Factory → notification channels, storage impls · Chain of Responsibility → gateway filters · Adapter → PSP/provider adapters · Template Method → base Kafka consumer with retry/DLQ hooks. `lld-lab` only covers what the flagship can't (pure state machines, from-scratch primitives).

### Cadence & definition of done

**~2-3 h/week carved from the 10-15 h budget**, concurrency front-loaded: wks 1-6 → builds 1-7, wks 7-11 → one machine-coding problem/week, wk 12 → re-drills. Per exercise: stress test green, README (trap + fix + 3-5 trade-offs + JMH numbers), timeboxed blank-file re-drill ≥2 wks later logged in `docs/drills/DRILL-LOG.md`.

---

## Kubernetes & deployment patterns

### Compose → k3d split rule

**Stateful infra stays in Docker Compose forever; app services move into k3d.** Running Kafka/OpenSearch/Postgres in-cluster means operators and PVC ops — low interview ROI, doubles RAM. Pods reach host infra via `host.k3d.internal` (e.g. `SPRING_KAFKA_BOOTSTRAP_SERVERS=host.k3d.internal:9092`). **One exception:** Redis runs *inside* k3d as the StatefulSet teaching example.

Declarative cluster config at `infra/k8s/k3d-config.yaml`: 1 server + **2 agents** (enables node-drain/PDB demos), built-in local registry on `localhost:5001`, Traefik ingress mapped to `localhost:8080`. k3s ships Traefik + metrics-server by default — both are load-bearing (Ingress, HPA).

**RAM budget:** cluster ~1.5-2 GB + 5-8 JVM services (`-Xmx256m`, 512Mi limits) ~2.5-4 GB + Compose infra (Kafka+SR ~1.5 GB, Postgres/MinIO ~300 MB, otel-lgtm ~1.5 GB) ≈ **8-10 GB peak**; OpenSearch (+1.5 GB) only when the search profile is active. 16 GB Mac: one compose profile at a time; 32 GB: everything at once.

### Manifest strategy: Kustomize

Kustomize over Helm for our manifests — you keep writing/patching the raw YAML interviews probe, kubectl-native, no templating DSL (Helm still used as a consumer for third-party installs like Linkerd/Argo).

```
infra/k8s/
├── k3d-config.yaml
├── base/
│   ├── namespace.yaml, ingress.yaml
│   ├── <service>/   # deployment.yaml (3 probes, requests/limits), service.yaml,
│   │                # hpa.yaml, pdb.yaml, configmap.yaml, kustomization.yaml
│   └── redis/       # statefulset.yaml + headless service (the StatefulSet example)
└── overlays/
    ├── dev/         # replicas=1, tiny resources, host.k3d.internal endpoints
    └── demo/        # replicas=3 — rollout/PDB/canary demos
```

### K8s concepts deliberately exercised (each with a demo)

| Concept | Demo |
|---|---|
| Rolling update + rollback | Broken image under k6 load → `kubectl rollout undo` with zero 5xx |
| HPA (CPU) | k6 ramp → scales 1→4 → cooldown scale-down |
| HPA (custom metric) | **KEDA** ScaledObject on **Kafka consumer lag** — flood topic, consumers scale |
| Readiness probe | Toggle actuator readiness → pod leaves Endpoints, no failed requests |
| PDB + drain | `kubectl drain` agent node with `minAvailable: 1` → evictions serialize |
| Requests/limits + OOMKill | 128Mi limit vs `-Xmx512m` → OOMKilled/CrashLoop → fix heap alignment |
| Ingress (L7) | Traefik host/path routing vs Service L4 |
| StatefulSet | Delete Redis pod → same identity + PVC reattach |
| Job/CronJob | Nightly cache-warmer CronJob; backoffLimit demo |
| NetworkPolicy | Default-deny + allow gateway→services only (k3s enforces it) |

### Deployment patterns

| Pattern | Laptop-lightest mechanism | Proof demo |
|---|---|---|
| Rolling | Deployment `maxSurge`/`maxUnavailable` | k6 through upgrade → 0 failures |
| **Canary** | **Traefik `TraefikService` weighted RR** stable/canary (no extra CRDs; Argo Rollouts = stretch) | 90/10 → watch canary error panel → promote or yank |
| Blue-green | Two Deployments `track: blue\|green`, flip `Service.spec.selector` | `X-Version` header flips atomically; flip back = instant rollback |
| Feature flags | **OpenFeature + flagd** (~20 MB container, ConfigMap JSON, hot-reload) | Deploy dark → flip flag, no redeploy → kill-switch drill under load |

### Inner loop: Tilt

Root `Tiltfile`: `k8s_yaml(kustomize('infra/k8s/overlays/dev'))`. Images: **JVM → Jib** (layered, no Dockerfile, via `custom_build` → `localhost:5001`); **Go → multi-stage → distroless**; **Python → `python:3.12-slim` multi-stage**. Target: change → running pod <30s Go, <90s JVM.

### Stretch decisions

- **Linkerd on k3d**: adds mTLS, zero-code golden metrics, `viz tap`, TrafficSplit canary. ~300-400 MB + 15-20 MB/sidecar. **Skip on 16 GB** or if Traefik canary + OTel already landed — marginal new concept is mTLS/tap only.
- **Argo CD**: skip for core roadmap (re-demonstrates `kubectl apply -k` at ~1 GB RAM). Optional one-evening stretch: one `Application` on `overlays/dev`, demo drift-detection/self-heal.

---

## Verification & interview-drill harness

Every concept claimed in the coverage matrix must be **provable on demand**: a k6 script that measures it, a chaos experiment that breaks it, or a demo scenario that shows it.

### Load-test suite — `load/`

```
load/
├── lib/helpers.js          # auth, warmup, custom metrics, summary export
├── thresholds.js           # shared presets
├── scenarios/              # cache-hit-ratio, rate-limiter-saturation, ws-connection-ramp,
│                           # saga-throughput, feed-fanout, search-latency, upload-multipart
└── NUMBERS.md              # measured-numbers ledger
```

- Threshold conventions: `p(95)<200, p(99)<500` reads; `p(95)<800` write/saga paths; `http_req_failed rate<0.01` (rate-limit tests count 429s via a custom `limited_requests` metric, not as failures). Every script exports `handleSummary()` → `load/results/<script>-<date>.json`.
- Key script patterns: **cache-hit-ratio** (cold vs warm stage, compare p95 + DB query rate), **rate-limiter-saturation** (ramp past limit → assert 429 rate ≈ excess, then assert token refill), **ws-connection-ramp** (5-10k conns, broadcast delivery latency), **saga-throughput** (clean run vs Toxiproxy-latency run, compare compensation count), **feed-fanout** (100:1 read:write, toggle fan-out-on-write vs on-read).
- **`NUMBERS.md` is the back-of-envelope ledger**: `endpoint | max sustained QPS @ p95 target | breaking point | bottleneck`. Interview estimation from *measured* single-node ceilings instead of memorized folklore.

### Chaos catalog — `chaos/`

`chaos/run.sh <experiment>` + `toxiproxy/` configs + `experiments/*.md` (each ≤1 page: Inject → Observe → Expect → Talking point). All inter-service/infra TCP hops route through Toxiproxy in the `chaos` compose profile. The 12 experiments:

| # | Experiment | Proves |
|---|---|---|
| 01 | `payment-latency-spike` (3s toxic) | circuit breaker CLOSED→OPEN, fail-fast vs cascade |
| 02 | `kafka-consumer-blackhole` | consumer lag/backpressure, DLQ after N retries |
| 03 | `db-primary-kill` | replica failover, replication lag, read-your-writes |
| 04 | `redis-flush-thundering-herd` | stampede defenses (coalescing, TTL jitter) |
| 05 | `duplicate-delivery-replay` (offset reset) | idempotent consumer; exactly-once = at-least-once + idempotency |
| 06 | `split-brain-lock` (pause holder past TTL) | fencing tokens; why TTL locks alone are unsafe |
| 07 | `retry-storm` (50% resets, jitter off→on) | retry budgets, metastable failure |
| 08 | `outbox-crash` (kill after commit, before publish) | dual-write problem, transactional outbox via Debezium |
| 09 | `saga-mid-flight-kill` | compensation spans visible in Tempo trace |
| 10 | `ws-node-kill` (1 of 2 gateway replicas) | reconnect, externalized connection state in Redis |
| 11 | `slow-object-store` (bandwidth toxic on MinIO) | multipart retry per-part, partial-failure isolation |
| 12 | `hot-key` (80% traffic to one key) | hot-partition mitigation (L1 cache, key salting) |

Run one experiment/week from Phase 2 onward; the full catalog is the final-phase "chaos day."

### Observability verification

Five provisioned Grafana dashboards (`infra/observability/dashboards/`): **Service Golden Signals** (RED per route, deploy markers), **Kafka Pipeline** (lag, DLQ depth, end-to-end latency), **Data Layer** (cache hit ratio, hot keys, replication lag), **Resilience** (breaker state timeline, retries, limiter allow/deny), **Business Flow** (orders by state, compensations/hour). Trace assertions in Tempo: one trace spanning gateway → services → *through Kafka* (context propagation over messaging); saga compensation as child spans. Loki: trace_id-linked log jump.

### Interview-drill kit — `docs/`

- `templates/design-doc.md` (headings: requirements → estimation w/ *measured reality* column from NUMBERS.md → API/idempotency/versioning → data model + shard keys → HLD diagram → 2-3 deep dives → failure modes linked to chaos experiments → rejected alternatives), `templates/adr.md`, `templates/estimation-worksheet.md`
- Per service: `docs/<service>/design.md` + `adr/NNN-*.md`
- **Drill loop**: ≥2 weeks after building a service, whiteboard it fresh in 35 min (5 req / 5 estimation / 10 HLD / 10 deep-dive / 5 failure modes), compare against the built reality + dashboards, log gaps in `drills/DRILL-LOG.md`, re-drill in two weeks.

### Definition of done — per service

- [ ] Starts via its compose profile; health green; K8s manifest applies cleanly (post-K8s phase)
- [ ] OTel wired: visible in Golden Signals; traces propagate in/out (incl. Kafka)
- [ ] ≥1 k6 scenario passing thresholds; row added to `NUMBERS.md`
- [ ] ≥1 chaos experiment documented + demonstrated
- [ ] `docs/<service>/design.md` + ≥1 real ADR
- [ ] README demo: ≤5 commands from `docker compose up` to observable behavior

---

## Immediate next steps (first working session, ~3h)

1. `mkdir /Users/sahanur/IdeaProjects/agora && git init` → private GitHub repo (user preference)
2. Root skeleton: `pom.xml` parent, `go.work`, `Makefile`, `.env.example`, `docs/templates/`, `docker-compose.yml` with `core` profile (Postgres + Redis + Redpanda + MinIO + otel-lgtm) — verify `make up PROFILE=core` on RAM budget
3. First service: `link-service` (Go URL shortener — small, instant win): Snowflake IDs, base62, cache-aside Redis, health endpoint, OTel, k6 baseline script → first row in `load/NUMBERS.md`
4. `lld-lab` module + exercise #1 (bounded blocking queue) in the same week — establishes the 2-track cadence
