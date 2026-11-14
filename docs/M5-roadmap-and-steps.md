# Vigilia Platform — M5 Roadmap & Implementation Guide
> **Role:** Member 5 (DevSecOps & MLOps Infrastructure)  
> **Status:** Phase 1–5 Complete & Operational | Current: Phase 6 (Helm)

---

## 🗺️ The Entire M5 Journey in One Picture

```
                         COMPLETED
                             ↓
                  ┌──────────────────────┐
                  │ 1. Docker Foundation │  (Postgres, MLflow, Prometheus, Grafana, Alertmanager, 8 App Stubs)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 2. CI/CD & Security  │  (Gitleaks, SonarCloud, Trivy blocking, Dependabot)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 3. GHCR Registry     │  (Multi-tag strategy: sha, semver, branch-latest)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 4. K8s Manifests     │  (Deployments, Services, Health Probes)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 5. K8s Configuration │  (ConfigMaps, Secrets, CPU/Mem Limits)
                  └──────────┬───────────┘
                             ↓
                        YOU ARE HERE
                             ↓
                  ┌──────────────────────┐
                  │ 6. Helm Charts       │  (Parameterize dev/prod environments)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 7. Ingress & TLS     │  (External routing, Gateway, TLS termination)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 8. CI/CD to K8s      │  (Automated Helm deployment & rollback)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 9. Monitoring & Drift│  (Prometheus, Grafana, Alertmanager + [NEW] Drift Monitoring)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 10. Autoscaling HPA  │  (Horizontal Pod Autoscaler under load)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 11. MLOps Lifecycle  │  (MLflow Registry + [NEW] Evidently Data & Concept Drift)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 12. Load Testing     │  (k6 traffic ramp-up: 10 -> 500 users)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 13. Chaos / HA Tests │  (Pod kill, DB failure, simultaneous load + chaos)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 14. Sec Hardening    │  (Non-root containers, RBAC, Trivy gates)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 15. Final Integration│  (Replace stubs with real M2/M3/M4 code)
                  └──────────┬───────────┘
                             ↓
                  ┌──────────────────────┐
                  │ 16. Final Demo (5 D) │  (End-to-End DevSecOps presentation)
                  └──────────────────────┘
```

---

## 🟦 PHASE 1 — Local Docker Foundation *(COMPLETED ✅)*

- [x] **Step 1 — 8 Application Service Containers:** All services (`api-gateway`, 6 Spring Boot backend services, `ml-serving`, `frontend`) have multi-stage Dockerfiles and are declared in `docker-compose.yml`.
- [x] **Step 2 — PostgreSQL Container:** `postgres:16-alpine` running with named persistent volume `postgres_data`, schema initialization, and healthcheck (`pg_isready`).
- [x] **Step 3 — Full Local Stack Verification:**
  - Stack boots via `docker compose up -d`.
  - Pinned observability image tags: `prom/prometheus:v2.55.1`, `prom/alertmanager:v0.27.0`, `grafana/grafana:11.3.2`.
  - All services equipped with healthchecks and dependencies (`depends_on: service_healthy`).
  - MLflow container operational (`vigilia-mlflow` on port 5000 with PostgreSQL backend).

---

## 🟦 PHASE 2 — Build CI/CD *(COMPLETED ✅)*

- [x] **Step 4 — GitHub Actions CI Pipeline:** `.github/workflows/ci.yml` runs checkout, Maven test/package, pytest for ML, npm build for frontend, and Docker image builds.
- [x] **Step 5 — Code Quality (SonarCloud):** Configured in `sonar-project.properties` and integrated into CI with Quality Gate enforcement.
- [x] **Step 6 — Container Security Scanning (Trivy):** Scans Docker images with `exit-code: 1` on CRITICAL/HIGH vulnerabilities, and scans filesystem & IaC in `security.yml`.
- [x] **Step 7 — Secret Scanning (Gitleaks):** First gate in CI pipelines; scans full repository commit history to block credential leaks.
- [x] **Step 8 — Dependency Security (Dependabot):** `.github/dependabot.yml` scans and automates updates for Maven, npm, pip, and Docker base images weekly.

---

## 🟦 PHASE 3 — Container Registry (GHCR) *(COMPLETED ✅)*

- [x] **Step 9 — Configure GHCR:** CI pushes multi-architecture images to `ghcr.io/<owner>/vigilia-<service>`.
- [x] **Step 10 — Image Versioning & Rollback Safety:** Pinned commit SHA tags (`:sha-<short>`), branch latest (`:develop-latest`, `:main-latest`), and semantic release tags (`:v1.0.0`, `:1.0`, `:1`, `:latest`) via `.github/workflows/cd.yml`.

---

## 🟦 PHASE 4 — Learn and Prepare Kubernetes *(MANIFESTS READY ✅)*

- [x] **Step 11 — Create Local Kubernetes Cluster:** Setup using Minikube or Kind. Verify with `kubectl get nodes`.
- [x] **Step 12 — Deploy ONE Service:** `infrastructure/kubernetes/api-gateway.yaml` created with Deployment and Service specs.
- [x] **Step 13 — Add Health Probes:** Configured across services:
  - `startupProbe`: Allows Spring Boot initialization grace period (up to 60s).
  - `livenessProbe`: Detects deadlocks and restarts unresponsive containers.
  - `readinessProbe`: Directs traffic only when the application is ready to accept requests.
- [x] **Step 14 — Deploy Remaining Services:** Manifests created for all services (`auth`, `customer`, `transaction`, `risk`, `notification`, `audit`, `ml-serving`, `frontend`, `postgres`).

---

## 🟦 PHASE 5 — Kubernetes Configuration *(COMPLETED ✅)*

- [x] **Step 15 — ConfigMaps:** `infrastructure/kubernetes/configmap.yaml` extracts environment variables, service discovery URLs, and non-sensitive database coordinates.
- [x] **Step 16 — Secrets:** `infrastructure/kubernetes/secret.example.yaml` templates database passwords and JWT secrets; loaded into pods via `secretKeyRef`.
- [x] **Step 17 — Resource Requests and Limits:** CPU and Memory requests and limits defined for all containers (foundational for HPA autoscaling).

---

## 🟦 PHASE 6 — Helm *(NEXT STEP ⏳)*

- [ ] **Step 18 — Create Helm Chart:**
  - Structure `helm/vigilia/`:
    ```
    helm/vigilia/
    ├── Chart.yaml
    ├── values.yaml
    └── templates/
        ├── deployment-api-gateway.yaml
        ├── deployment-backend.yaml
        ├── deployment-ml.yaml
        ├── service.yaml
        ├── configmap.yaml
        └── secret.yaml
    ```
  - Convert static Kubernetes YAMLs into parameterized Helm templates.
- [ ] **Step 19 — Configurable Environments:**
  - Create `values-dev.yaml` (minimal replicas, lower resource limits).
  - Create `values-prod.yaml` (multi-replica, strict limits, production image tags).

---

## 🟦 PHASE 7 — Kubernetes Networking

- [ ] **Step 20 — Internal Service Communication:** Validate CoreDNS name resolution between services (e.g. `http://risk-management-service:8084`).
- [ ] **Step 21 — External Access (Ingress / Gateway):** Deploy Ingress controller (e.g., NGINX Ingress Controller) routing external traffic: `Internet → Ingress → API Gateway → Internal Microservices`.
- [ ] **Step 22 — HTTPS / TLS Termination:** Configure TLS termination at the Ingress layer using certificates (self-signed or cert-manager).

---

## 🟦 PHASE 8 — CI/CD → Kubernetes

- [ ] **Step 23 — Extend GitHub Actions:** Connect pipeline: `Push → Tests → SonarQube → Gitleaks → Trivy → GHCR → Helm Upgrade → Kubernetes`.
- [ ] **Step 24 — Automated Deployment:** CD deploys automatically to local/development cluster on merges to `develop`.
- [ ] **Step 25 — Automated Rollback Demonstration:**
  - Deploy Version 1.
  - Deploy Version 2 (simulated failure).
  - Trigger automated rollback (`helm rollback` / K8s rollout undo) back to Version 1.

---

## 🟦 PHASE 9 — Monitoring & Observability

- [ ] **Step 26 — Prometheus Kubernetes Monitoring:** Monitor pods, services, nodes, request rates, error rates, and deploy `kube-state-metrics`.
- [ ] **Step 27 — Grafana Dashboards:**
  - **Dashboard 1 — Application:** Request rates, p95/p99 latency, HTTP 5xx errors, throughput.
  - **Dashboard 2 — Kubernetes Cluster:** Pod status, CPU/Memory utilization vs limits, replica counts, restart counts.
  - **Dashboard 3 — ML Serving & Observability:** Inference count, latency, model versions, uncertainty metrics.
- [ ] **Step 28 — Alertmanager Scenarios:**
  - Test firing and notification routing: `ServiceDown`, `HighLatency`, `HighErrorRate`, `PodCrashLoop`.

---

## 🟦 PHASE 10 — Autoscaling (HPA)

- [ ] **Step 29 — Configure Horizontal Pod Autoscaler (HPA):**
  - Set CPU/Memory scaling triggers (e.g. target 60% CPU utilization, min 2 replicas, max 6 replicas).
- [ ] **Step 30 — Live HPA Verification:** Run load generation to watch `kubectl get hpa` dynamically scale up pod replicas and scale down after cooldown.

---

## 🟦 PHASE 11 — MLOps & Model Lifecycle

- [x] **Step 31 — Model Delivery Architecture:** MLflow Registry backed by PostgreSQL. ML serving downloads `@champion` models dynamically on startup.
- [x] **Step 32 — Deploy MLflow Server:** Live containerized server on port 5000 with persistent artifact volume and health checks.
- [x] **Step 33 — Model Versioning & Dual Model Setup:**
  - `purchase-fraud-model` (`@champion`) — 29 purchase features.
  - `transfer-fraud-model` (`@champion`) — 20 transfer features.
  - `fraud-model` (`@champion`) — legacy fallback alias.
- [ ] **Step 34 — Automated Model Promotion Pipeline:** Script/job promoting candidate models from `@challenger` to `@champion`.
- [ ] **Step 35 — ML Service Observability:** Prometheus scraping inference metrics from `/metrics` (`fintech_ml_predictions_total`, `fintech_ml_inference_duration_seconds`).
- [ ] **✨ [NEW ADDITION] Step 35B — Data & Concept Drift Monitoring:**
  - **Ownership:** Joint MLOps (DevSecOps + M1/M2).
  - **Mechanism:** Using **Evidently AI** or statistical Kolmogorov-Smirnov / PSI divergence tests.
  - **How it works:**
    1. Compare live inference feature distributions (amount, location, velocity) against M1's baseline training distributions (`golden_rows` / baseline profiles).
    2. Expose drift metric to Prometheus: `fintech_feature_drift_score{feature="amount"}`.
    3. Trigger Alertmanager alert if drift exceeds tolerance (`fintech_data_drift_detected > 0`).
    4. Display drift metrics on Grafana Dashboard 3 (ML Monitoring).

---

## 🟦 PHASE 12 — Load Testing (k6)

- [ ] **Step 36 — Build k6 Test Scripts:** Simulate realistic payment traffic ramps (10 VUs → 50 VUs → 100 VUs → 500 VUs).
- [ ] **Step 37 — Observe System Under Load:** Correlate k6 traffic spikes with Prometheus metrics, Grafana dashboards, and HPA pod scaling events.

---

## 🟦 PHASE 13 — High Availability & Chaos Testing

- [ ] **Step 38 — Single Pod Termination:** `kubectl delete pod` during traffic; measure recovery time and zero-downtime handling.
- [ ] **Step 39 — Multi-Pod Chaos:** Kill multiple replicas; verify service continuity.
- [ ] **Step 40 — Service Failure Simulation:**
  - Take `ml-serving` offline.
  - Verify `risk-management-service` gracefully falls back to `PENDING_REVIEW` (never unhandled 500).
  - Verify `MLServiceDown` alert fires in Alertmanager.
- [ ] **Step 41 — Database Outage Simulation:** Temporarily disconnect PostgreSQL; evaluate retry logic and alert generation.
- [ ] **Step 42 — Simultaneous Chaos + Load Test:** Run k6 load test while terminating pods; capture Grafana evidence of self-healing under stress.

---

## 🟦 PHASE 14 — Security Hardening

- [x] **Step 43 — Scan Images with Trivy:** Blocking on CRITICAL/HIGH CVEs with fixes enabled.
- [x] **Step 44 — Static Code & Dependency Audit:** SonarCloud Quality Gate + Dependabot updates.
- [x] **Step 45 — Secret Scanning:** Gitleaks pre-commit/CI enforcement.
- [ ] **Step 46 — Kubernetes Security Hardening:**
  - Enforce non-root execution (`runAsNonRoot: true`).
  - Read-only root filesystems where applicable.
  - Principle of least privilege for ServiceAccounts and RBAC.

---

## 🟦 PHASE 15 — Final Integration

- [x] **Step 47 — ML Model Integration:** Real dual champion models (`purchase-fraud-model` and `transfer-fraud-model`) integrated and serving live predictions.
- [ ] **Step 48 — Connect Real Spring & Frontend Implementations:** Replace stubs with final M3 business logic and M4 UI components.
- [ ] **Step 49 — End-to-End Regression Test:** Validate full chain across CI/CD, Docker, Kubernetes, Helm, Alerts, HPA, and Load Testing.

---

## 🟦 PHASE 16 — Final Project Demonstrations

Prepare 5 focused, high-impact demonstration workflows:
1. **Demo 1 — Secure GitOps Deployment:** `git push` → Gitleaks → SonarCloud → Trivy → GHCR → Helm → Kubernetes rollout.
2. **Demo 2 — Full-Stack Observability:** Live Grafana dashboards displaying cluster metrics, service latencies, and business throughput.
3. **Demo 3 — Horizontal Autoscaling (HPA):** k6 load generation triggering dynamic pod replication in real time.
4. **Demo 4 — Resilience & Self-Healing:** Terminating pods and services under load with automatic recovery and fallback verification.
5. **Demo 5 — MLOps & Drift Intelligence:** Live fraud transaction scoring, model metrics in Grafana, and live data drift detection alerting.

