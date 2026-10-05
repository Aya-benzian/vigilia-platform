# Open Decisions

Do not hide these decisions inside one component. Agree with the listed members and then
record the result under `contracts/`.

## Locked Decisions — 2026-10-05 (Team lead: Aya-benzian)

| Decision | Outcome |
| --- | --- |
| Runtime and observability contract | **Accepted v1.0** — binding for all components |
| Notification/audit communication | **REST** — messaging added only if justified later |
| Inter-service authentication | **JWT** — stateless, compatible with Spring Boot and FastAPI |
| CI/CD platform | **GitHub Actions** |
| Load testing tool | **k6** |
| Kubernetes packaging | **Helm** |
| Container registry | **GHCR** (`ghcr.io/aya-benzian/vigilia-<service>`) |
| Database engine | **PostgreSQL** |

## Remaining Open Decisions

| Decision | Members |
| --- | --- |
| ML features, preprocessing, artifact format | 1, 2 |
| Uncertainty meaning and decision thresholds | 1, 2, 3 |
| Prediction, error, explanation, and visualization schemas | 2, 3, 4 |
| Roles, permissions, transaction and review APIs | 3, 4 |
| ML timeout, retries, circuit breaker, fallback | 2, 3 |
| Local orchestration, Kubernetes packaging, CI gates | 5 with component owners |
