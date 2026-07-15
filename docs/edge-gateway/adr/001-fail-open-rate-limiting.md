# ADR-001: Fail OPEN when the rate limiter is unavailable

**Status:** accepted · **Date:** 2026-07-14

## Context
Every request pays a gRPC hop to the rate-limiter (50ms hard deadline). The limiter can be slow,
restarting, or down.

## Decision
On DEADLINE_EXCEEDED / UNAVAILABLE the gateway **allows the request**, increments a fail-open
counter, and WARN-logs (throttled 1-in-N). Kill switch: `RL_ENABLED=false` bypasses entirely.

## Alternatives
**Fail closed** (reject when limiter is down): turns a protection-component outage into a full
product outage — correct only when the limiter guards something billable/abusive enough that
over-admission is worse than downtime (e.g. paid quota enforcement). That variant is a config
choice away; the default optimizes availability.

## Consequences
During limiter outages the system runs unprotected (bounded by the outage window). Observed in
practice: the FIRST request after gateway boot failed open because channel warm-up exceeded the
deadline — a cold-start lesson worth telling in interviews.
