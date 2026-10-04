# Requirements Traceability

The professor's PDF remains authoritative. This table maps each required area to its
future implementation and evidence location; it does not claim that the requirement is
already implemented.

| Requirement | Planned location | Expected evidence | Status |
| --- | --- | --- | --- |
| Spring Boot microservices and gateway | `backend/` | APIs, security tests, service documentation | Foundation only |
| Five-model benchmark | `ml/training/` | Reproducible runs and metric comparison | Foundation only |
| Statistical validation | `ml/training/` | Friedman/Wilcoxon, Holm, intervals, effect sizes | Foundation only |
| Uncertainty and human review | `ml/serving/`, risk service, frontend | Method, thresholds, review workflow | Foundation only |
| MLflow pipeline and registry | `ml/training/`, `ml/serving/` | Runs, artifacts, versions, promotion record | Foundation only |
| FastAPI model serving | `ml/serving/` | Prediction/health/metrics APIs and integration tests | Foundation only |
| PCA/t-SNE/UMAP visualization | ML workspaces and `frontend/` | Generated data and dashboard | Foundation only |
| Docker and Kubernetes | `infrastructure/` and component Dockerfiles | Manifests, probes, HPA, rollback demonstration | Foundation only |
| Load and high-availability experiments | `infrastructure/load-testing/` | Normal/high/overload/recovery and failure results | Foundation only |
| Prometheus and Grafana | `infrastructure/observability/` | Metrics, dashboards, alert rules | Foundation only |
| CI/CD and DevSecOps | `.github/workflows/`, `infrastructure/ci-cd/` | Build, tests, SonarQube, scan, deploy, rollback | Foundation only |
| GitHub repository and Markdown report | Repository root and `docs/` | Reviewable history and final report | In progress |
| Five-minute demonstration | Member 4 coordination; documented later | Repeatable demo script | Not started |
