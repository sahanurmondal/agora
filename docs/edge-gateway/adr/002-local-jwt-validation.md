# ADR-002: Validate JWTs locally at the gateway (no introspection call)

**Status:** accepted · **Date:** 2026-07-14

## Context
Every authenticated request needs identity. Options: call identity-service per request
(introspection), or verify the HS256 signature locally with the shared secret.

## Decision
**Local validation.** The gateway verifies signature + expiry itself and forwards identity as
`X-User-Id`/`X-User-Name` (always stripping client-supplied values first — spoofing defense).
Upstream services trust those headers only from the gateway.

## Alternatives
- **Introspection per request**: adds a network hop to every call and makes identity-service a
  hard dependency of all traffic — rejected for the hot path.
- **RS256 + JWKS**: right answer once >1 party validates tokens (no shared secret distribution);
  planned upgrade, config-compatible.

## Consequences
Revocation lags until token expiry (1h) — mitigated by short TTLs; instant revocation would need
a denylist cache (Redis) checked at the edge. This latency-vs-revocation trade-off is the exact
interview follow-up this ADR exists to answer.
