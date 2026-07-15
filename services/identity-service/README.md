# identity-service

AuthN provider: register/login/me, BCrypt hashes, HS256 JWT issuance (1h TTL). Auth core
harvested from the user's MyChatApp (deliberate reuse). Stateless by design — the gateway
validates tokens locally (see edge-gateway ADR-002); this service is only on the login path.

## API
`POST /api/v1/auth/register` {username,password} → 201 · `POST /api/v1/auth/login` → {token, expires_in} · `GET /api/v1/auth/me` (Bearer)

## Config (env)
`IDENTITY_PORT` (8082) · `JWT_SECRET` (shared with gateway) · Postgres `identitydb` @ localhost:5433

## Demo
```bash
java -jar services/identity-service/target/*.jar &
curl -X POST localhost:8082/api/v1/auth/register -H 'Content-Type: application/json' -d '{"username":"u1","password":"p1"}'
curl -X POST localhost:8082/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"u1","password":"p1"}'
```
ADR (design doc): stateless HS256 now; RS256/JWKS when a second validator appears; revocation =
short expiry (accepted trade-off).
