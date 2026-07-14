# <Service> — Design Doc

## 1. Problem & requirements
- Functional:
- Non-functional (SLOs, scale targets):
- Out of scope:

## 2. Estimation
| Metric | Predicted (back-of-envelope) | Measured reality (from load/NUMBERS.md) |
|---|---|---|
| QPS (read / write) | | |
| Storage /day, /year | | |
| Bandwidth | | |

## 3. API
Endpoints, request/response, versioning (`/v1/`), idempotency keys on mutating POSTs, error model.

## 4. Data model
Schema, indexes, shard/partition key, growth pattern.

## 5. High-level design
Diagram + one paragraph per component.

## 6. Deep dives (2-3 hard parts)

## 7. Failure modes
Each linked to its chaos experiment in `chaos/experiments/`.

| What breaks | Blast radius | Mitigation | Proven by |
|---|---|---|---|

## 8. Trade-offs & rejected alternatives
