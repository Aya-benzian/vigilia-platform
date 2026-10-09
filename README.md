# FinTech MLOps Platform

University project for a high-availability fraud/risk platform combining Spring Boot,
React, FastAPI, machine learning, MLflow, Kubernetes, CI/CD, Prometheus, and Grafana.

The professor's [`project specification`](Projet%20ML-jee%20LSI%202026.pdf) is the primary
source of truth. [`Roles.md`](Roles.md) and [`Datasets.md`](Datasets.md) contain the team's
responsibilities and dataset decisions.

## Architecture

```text
React -> API Gateway -> Transaction Service -> Risk Management -> FastAPI ML
                                      |                |
                                      v                v
                              Notification/Audit   Human review
```

The ML service returns prediction evidence. The risk-management service makes the final
`APPROVED`, `REJECTED`, or `PENDING_REVIEW` business decision.

## Repository

| Path | Responsibility | Owner |
| --- | --- | --- |
| `ml/training/` | Data, training, evaluation, statistics, calibration, MLflow | Member 1 |
| `ml/serving/` | FastAPI, uncertainty, explainability, ML metrics | Member 2 |
| `backend/` | Gateway, auth, customer, transaction, risk services | Member 3 |
| `frontend/` | React customer and analyst interfaces | Member 4 |
| `backend/notification-service/` | Notifications | Member 4 |
| `backend/audit-service/` | Audit history | Member 4 |
| `infrastructure/` | Docker, Kubernetes, CI/CD, monitoring, load testing | Member 5 |
| `contracts/` | Shared API, ML, event, and observability contracts | Shared |
| `tests/integration/` | Cross-component tests | Shared |

Each component owns its dependencies, tests, configuration, and eventual Dockerfile.
Components integrate through contracts, not shared source code or database tables.

## Dataset Decision

- Fraud Detection Handbook simulator: primary ML experiment.
- PaySim: separate robustness experiment.
- Never merge the two datasets or claim real-bank generalization.
- Never commit datasets, generated models, MLflow runs, or secrets.

## Running the Platform Locally

All platform infrastructure (8 application microservices, PostgreSQL, Prometheus, Grafana, and Alertmanager) can be started with Docker Compose.

> [!IMPORTANT]
> **Do not forget to create your `.env` file locally before starting!**  
> Actual secrets and environment configurations are strictly gitignored and never committed. You **must** create `.env` from the provided template:
> ```bash
> cp .env.example .env
> ```

Once `.env` is created, run:

```bash
# Build and launch all 13 containers in detached mode
docker compose up -d --build

# Verify that all containers are healthy and running
docker compose ps
```

To shut down the platform:
```bash
docker compose down
```

## Start Here

1. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and your ownership area.
2. Review [`docs/open-decisions.md`](docs/open-decisions.md) before defining shared behavior.
3. Agree on interfaces under `contracts/` before integrating components.
4. Keep implementation, dependencies, and tests inside your workspace.

Useful references:

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/ownership.md`](docs/ownership.md)
- [`docs/requirements-traceability.md`](docs/requirements-traceability.md)
- [`contracts/CONVENTIONS.md`](contracts/CONVENTIONS.md)
- [`contracts/observability/runtime-v0.1.md`](contracts/observability/runtime-v0.1.md)

Current phase: repository foundation. Application code and deployment tooling will be
added progressively. Deadline: **January 10, 2027**.
