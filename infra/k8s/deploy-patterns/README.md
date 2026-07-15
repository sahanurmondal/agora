# Deployment patterns (k3d)

## Rolling update + rollback (default Deployment) — ✅ demoed
```bash
kubectl -n agora set image deploy/link-service link-service=agora-registry:5001/link-service:broken
kubectl -n agora rollout status deploy/link-service --timeout=30s   # fails: ImagePullBackOff
kubectl -n agora rollout undo deploy/link-service                   # instant rollback
```
Old ReplicaSet keeps serving throughout — zero 5xx if run under k6 (maxUnavailable honors readiness).

## Canary — Traefik weighted (no extra CRDs beyond what k3s ships)
`canary.yaml` here: a `link-service-canary` Deployment (1 replica, new tag) + Service, and a
`TraefikService` splitting weight 90/10 between stable and canary Services; the Ingress then
points at the TraefikService instead of the plain Service.
Demo: watch per-version error rate in Grafana → promote by shifting weights (90/10 → 50/50 → 0/100)
→ or yank canary to weight 0 = instant rollback. Argo Rollouts automates the same loop (stretch).

## Blue-green — Service selector flip
Two Deployments labeled `track: blue` / `track: green`; the Service selects one track.
```bash
kubectl -n agora patch svc link-service -p '{"spec":{"selector":{"app":"link-service","track":"green"}}}'
```
Atomic flip, instant rollback = flip back. Costs 2× capacity while both run — the classic trade-off vs canary.

## Feature flags — deploy ≠ release
Current: env flags (`FANOUT_MODE`, `RL_ALGORITHM`). Upgrade path: OpenFeature + flagd
(ConfigMap-backed JSON, hot reload) so a flag flip needs NO restart — the kill-switch drill.
