# Ownership

| Member | Paths | Responsibility |
| --- | --- | --- |
| 1 | `ml/training/` | Data preparation, five models, evaluation, statistics, calibration, MLflow |
| 2 | `ml/serving/` | FastAPI, uncertainty, explainability, visualization data, ML metrics |
| 3 | `backend/` except notification/audit | Gateway, security, core services, persistence, final decisions |
| 4 | `frontend/`, notification/audit services | Customer/analyst UI, notifications, audit, demo experience |
| 5 | `infrastructure/` | Docker, Kubernetes, CI/CD, security scans, monitoring, load/failure tests |

Shared contracts require reviews from both producer and consumer. Member 5 reviews
runtime, container, observability, and deployment changes. Add `.github/CODEOWNERS` after
the team's GitHub usernames are known.

Critical handoffs:

- Member 1 -> 2: feature schema, preprocessing, artifact, version, metrics, calibration.
- Member 2 -> 3: prediction API, uncertainty meaning, errors, timeout behavior.
- Member 2 -> 4: explanation and visualization schemas.
- Member 3 -> 4: transaction, authentication, and analyst-review APIs.
- All members -> 5: build/run commands, ports, health, metrics, config, and resources.
