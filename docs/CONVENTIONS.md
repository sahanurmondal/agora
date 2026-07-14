# Conventions

- **Commits:** Conventional Commits scoped by service — `feat(link-service): …`, `chore(infra): …`
- **Branches:** trunk-based; short-lived `feat/<svc>-<topic>`; squash merge
- **ADRs:** repo-wide `docs/adr/NNN-*.md`; per-service `docs/<service>/adr/NNN-*.md`
- **Definition of done (per service):** compose profile boots + health green · OTel visible in Grafana · ≥1 k6 scenario passing thresholds + `load/NUMBERS.md` row · ≥1 chaos experiment · design doc + ≥1 ADR · README with ≤5-command demo
- **Wire contracts:** defined only in `contracts/` (proto/Avro/OpenAPI/Pact) — never a shared domain jar
- **Secrets:** only in gitignored `.env`; every knob documented in `.env.example`
