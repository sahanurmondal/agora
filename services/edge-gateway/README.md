# edge-gateway

Single edge (Spring Cloud Gateway/WebFlux): L7 routing, JWT edge-auth, distributed rate limiting
over gRPC. Concepts: API gateway, fail-open resilience (ADR-001), local JWT validation trade-off
(ADR-002), header-spoofing defense, W3C trace propagation to upstreams.

## Routes
`/api/v1/auth/**`→identity · `/api/v1/links/**`→link (POST needs JWT) · `/api/v1/products|media/**`→catalog (JWT) ·
`/api/v1/search/**`→search · `/api/v1/orders/**`→order (JWT) · `/r/{code}`→link redirect

## Config (env)
`EDGE_PORT` (8080) · `JWT_SECRET` · `RATELIMIT_ADDR` (localhost:9095) · `RL_ENABLED` · per-route `*_URI` overrides

## Demo (≤5 commands)
```bash
mvn -q -f services/edge-gateway/pom.xml package -DskipTests && java -jar services/edge-gateway/target/*.jar &
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"staff_eng","password":"Passw0rd!"}' | jq -r .token)
curl -X POST localhost:8080/api/v1/links -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"url":"https://x.com"}'
seq 1 300 | xargs -P30 -I{} curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/r/<code> | sort | uniq -c   # exact 429 split
```
Notes: `json-to-grpc` autoconfig disabled (needs non-shaded grpc-netty in fat jars); gRPC channel
built on shaded NettyChannelBuilder directly (ServiceLoader unreliable in Boot fat jars).
