# Capacity Estimation — <System>

## Inputs (state your assumptions)
- DAU:                     | Requests/user/day:
- Read:write ratio:        | Payload size:
- Retention:               | Peak multiplier (vs avg):

## Derived
- Avg QPS = DAU × req/user ÷ 86,400 =
- Peak QPS = avg × peak multiplier =
- Storage/day = writes/day × payload =
- Storage/5yr (+ replication ×3) =
- Bandwidth = QPS × payload =
- Cache (80/20 rule: 20% of daily reads) =

## Sanity anchors (from load/NUMBERS.md — measured, not folklore)
| Component | Measured ceiling on this laptop | Assumed prod ceiling |
|---|---|---|

## Conclusion forced by the numbers
(e.g. "700 GB/yr → single Postgres fine for 2 yrs; 40k peak QPS reads → needs cache + replicas")
