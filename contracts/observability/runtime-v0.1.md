# Runtime and Observability Contract

**Status:** Accepted v1.0 — 2026-10-05  
**Owner:** Member 5  
**Decision authority:** Team lead (Aya-benzian)

## Services

| Service | Port | Owner |
| --- | ---: | --- |
| `api-gateway` | 8080 | 3 |
| `auth-service` | 8081 | 3 |
| `customer-service` | 8082 | 3 |
| `transaction-service` | 8083 | 3 |
| `risk-management-service` | 8084 | 3 |
| `notification-service` | 8085 | 4 |
| `audit-service` | 8086 | 4 |
| `ml-serving` | 8000 | 2 |
| `frontend` development / production | 5173 / 8087 | 4 |

Reserved local ports: MLflow `5000`, PostgreSQL `5432`, Prometheus `9090`, Grafana
`3000`, Alertmanager `9093`. Containers call each other by service DNS name, never
`localhost`.

## Health

| Runtime | Liveness | Readiness | Metrics |
| --- | --- | --- | --- |
| Spring Boot | `/actuator/health/liveness` | `/actuator/health/readiness` | `/actuator/prometheus` |
| FastAPI | `/health/live` | `/health/ready` | `/metrics` |
| Frontend container | `/health/live` | `/health/ready` | Not required initially |

- Healthy is HTTP `200`; unready is HTTP `503`.
- Liveness checks the process only. Readiness checks whether it can receive traffic.
- ML readiness requires a loaded model.
- Health and metrics stay private and never expose secrets or detailed errors.

## Configuration

Use uppercase `SNAKE_CASE` environment variables. Every service defines:

- `APP_NAME`, `APP_ENV`, `APP_VERSION`, `LOG_LEVEL`
- `SERVER_PORT` for Spring or `PORT` for FastAPI/frontend containers
- Component variables in a committed `.env.example`

Never commit `.env` or secrets. Use ConfigMaps for non-secret values and Secrets for
sensitive values. `VITE_*` values are public browser configuration and cannot contain
secrets.

## Logging and Correlation

Write one JSON log object per line to stdout with:

```text
timestamp, level, service, environment, version, message, correlation_id
```

Use UTC ISO 8601 timestamps and `snake_case` fields. Do not log credentials, tokens,
payment details, personal data, or raw ML features.

The gateway accepts or generates `X-Correlation-ID`. Every service propagates it,
includes it in request logs, and returns it in the response header.

## Prometheus Metrics

Required common metrics:

```text
fintech_build_info{service,version}
fintech_http_requests_total{service,method,route,status_code}
fintech_http_request_duration_seconds{service,method,route}
```

Planned ML/risk metrics:

```text
fintech_ml_predictions_total{prediction,model_version}
fintech_ml_inference_duration_seconds{model_version}
fintech_ml_prediction_uncertainty{model_version}
fintech_risk_decisions_total{decision,model_version}
```

Labels must be low-cardinality. Never use customer, transaction, correlation, email, raw
URL, error message, or timestamp values as labels. Use route templates, not concrete URLs.

## Docker

Image format:

```text
ghcr.io/<github-owner>/fintech-<service>:<git-sha>
```

Release images may also use `vX.Y.Z`; deployments must not use `latest`. Each service owns
its Dockerfile. Images must be multi-stage where useful, minimal, non-root, configured at
runtime, and free of secrets, datasets, caches, and build tools.

Only the frontend and API gateway may be public. Services, databases, MLflow, Actuator,
metrics, Prometheus, Grafana, and Alertmanager remain private.

## Team Approval

Each owner must confirm their service name, port, health behavior, environment variables,
logging, metrics, and build/run commands. After review, apply agreed changes and mark this
contract **Accepted v1.0** with the meeting date.
