# rate-limiter

Distributed rate limiting as a gRPC service (Go). Exists to exercise: **token bucket +
sliding-window counter as atomic Redis Lua** (no check-then-set race), gRPC contract-first
(`contracts/proto/ratelimit.proto`), and the **fail-open** consumer pattern (the gateway decides;
this service fails fast).

## Config (env)
`RATELIMIT_GRPC_PORT` (9095) · `RL_REDIS_ADDR` (localhost:6380) · `RL_ALGORITHM` (token_bucket|sliding_window) · `RL_RATE` (50/s) · `RL_BURST` (100)

## Docs
`docs/rate-limiter/design.md` · `docs/rate-limiter/adr/001-token-bucket-default.md`

## Demo (≤5 commands)
```bash
cd services/rate-limiter && go build -o bin/rate-limiter ./cmd/ratelimiter
RL_REDIS_ADDR=localhost:6380 RL_RATE=50 RL_BURST=100 ./bin/rate-limiter &
go run ./cmd/rlcheck        # first calls allowed → hammer → allowed=false + retry_after_ms
# through the gateway: seq 1 300 | xargs -P30 curl → observe exact 429 split
```
Measured: 300-req parallel burst → **123 allowed / 177×429** (= burst 100 + ~23 refill). `load/NUMBERS.md`.
