# Vigilia: Resilient FinTech Fraud and Risk Detection Platform
## System Conception Document — Version Final v4

**Version:** Final v4, Complete System Conception  
**Date:** October 2026  
**Status:** Ready for team alignment before development  
**Context:** Academic project. Only simulated or public research data is used. No real payments, no real customer data.  
**Document purpose:** Single source of truth for Vigilia's architecture, data design, ML lifecycle, API contracts, security, resilience and deployment.

---

## Table of Contents
1. System Overview
2. Product Concept
3. System Scope and Boundary
4. Actors and Roles
5. Functional Requirements
6. Non-Functional Requirements
7. Use Case Diagram
8. System Architecture
9. Service Specifications
10. Transaction-Type Routing and Decision Policy
11. Transaction Lifecycle and State Machines
12. Sequence Diagrams
13. Event Architecture (Transactional Outbox)
14. Machine Learning Architecture
15. FastAPI Model Serving Specification
16. Database Architecture (PostgreSQL)
17. API Endpoint Reference
18. Error Handling Conventions
19. Security and Access Control
20. Resilience Design
21. Observability and Monitoring
22. Kubernetes Deployment
23. CI/CD and DevSecOps
24. Testing and Experiment Strategy
25. Frontend Specification
26. Demo Data and Simulation
27. Risk Register
28. Open Decisions and Assumptions
29. Delivery Roadmap
30. Final Product Story

---

## 1. System Overview

### 1.1 System Name
Vigilia: Resilient FinTech Fraud and Risk Detection Platform

### 1.2 System Type
Cloud-native, microservice-based fraud and risk detection platform for two financial transaction domains: merchant payments and peer-to-peer money transfers. Combines a transactional core, domain-specific ML models, human-in-the-loop review and production-grade MLOps and observability.

### 1.3 Purpose
Vigilia supports two financial use cases:
1. **Merchant payment (`PURCHASE`):** Customer buys a product on an external/demo merchant platform and is redirected to Vigilia Hosted Checkout.
2. **Money transfer (`TRANSFER`):** Customer sends money directly to another Vigilia customer from Customer Dashboard.

Each transaction is classified by domain and evaluated by a specialized fraud model returning: prediction label, calibrated probability, uncertainty value, model version.
The backend always makes the final business decision: `APPROVED`, `REJECTED`, or `PENDING_REVIEW`.

### 1.4 Problem Statement
- **Merchant payments:** Fraud depends on amount, merchant, category, location vs customer home, timestamp, customer purchasing behavior.
- **Money transfers:** Fraud depends on sender, recipient, amount, frequency, sender-recipient relationship, prior transfer patterns.
Forcing both into one generic model degrades both. Vigilia uses **two domain-specific models** trained independently.

### 1.5 Objectives and Success Criteria
- **O1 (Detection):** Each model beats logistic-regression baseline on PR-AUC with statistical significance (Holm-corrected $p < 0.05$).
- **O2 (Calibration):** Expected Calibration Error ($\text{ECE}) \le 0.02$ on held-out test set.
- **O3 (Safe automation):** 0 transactions auto-approved or auto-rejected when ML is unavailable, invalid, or highly uncertain.
- **O4 (Human review):** Every `PENDING_REVIEW` transaction creates exactly one review case resolved by an analyst.
- **O5 (Resilience):** Pod failure and ML outage cause zero lost transactions and zero lost events.
- **O6 (Scalability):** HPA scales Risk and ML serving under load.
- **O7 (Traceability):** Every decision is reproducible (model version, feature snapshot, policy version, timestamp stored).
- **O8 (Merchant integration):** Demo merchant completes payment using public payment API and webhook.

### 1.6 Core Design Principles
1. **The model advises, the backend decides:** ML never changes transaction state directly.
2. **Fail safe, not fail open:** Any uncertainty or error leads to `PENDING_REVIEW`, never auto-approval.
3. **Domain-specific models:** No assumption that both domains use the same algorithm.
4. **Immutable traceability:** Decisions, model versions, features, and reviews are append-only.
5. **Service ownership:** Each service owns its schema. No cross-schema writes.
6. **Reliable events:** Transactional outbox pattern in the same database transaction as the state change.
7. **Idempotency:** Everywhere money or decisions are involved.
8. **Least privilege & defense in depth:** API, network, database, container levels.

### 1.7 Technology Stack
- **Frontend:** React 18 + TypeScript (Vite), React Router, TanStack Query.
- **API Gateway:** Spring Cloud Gateway.
- **Backend Microservices:** Java 21, Spring Boot 3.x, Flyway, Resilience4j, Micrometer.
- **ML Training:** Python 3.11/3.12, scikit-learn, XGBoost, imbalanced-learn, SHAP, Optuna, SciPy/statsmodels.
- **ML Serving:** FastAPI, Uvicorn, Pydantic v2.
- **Model Lifecycle:** MLflow (tracking & registry with aliases), MinIO.
- **Database:** PostgreSQL 15+ (one instance, logical schemas per service).
- **Orchestration & Deploy:** Kubernetes, Helm.
- **Observability:** Prometheus, Grafana, Alertmanager, structured JSON logs.
- **CI/CD & Security:** GitHub Actions, SonarQube, Trivy.
- **Testing:** JUnit 5, Testcontainers, pytest, k6/Locust.

---

## 8. System Architecture & Containers
- `api-gateway` (Port 8080)
- `auth-service` (Port 8081, schema `auth`)
- `customer-service` (Port 8082, schema `customer`)
- `transaction-service` (Port 8083, schemas `merchant` & `transaction`)
- `risk-service` (Port 8084, schemas `risk` & `review`)
- `notification-service` (Port 8085, schema `notification`)
- `audit-service` (Port 8086, schema `audit`)
- `fastapi-serving` (Port 8000, internal ML endpoints)
- `frontend` (Port 5173 dev / 8087 prod)
- `demo-merchant` (Port 3001)

---

## 10. Transaction-Type Routing and Decision Policy

```
Transaction -> Risk Management -> Check Hard Rules (Step 0)
                                 -> Route by transaction_type:
                                     - PURCHASE -> Build purchase features -> POST /predict/purchase
                                     - TRANSFER -> Build transfer features -> POST /predict/transfer
                                 -> Validate ML Response (Step 2)
                                 -> Apply Decision Policy (Steps 1-6)
                                 -> Decision: APPROVED / REJECTED / PENDING_REVIEW
```

### Policy Steps (Ordered, First Match Wins)
- **Step 0:** Hard business rule hit:
  - Blocklisted entity -> `REJECTED` (`HARD_RULE_BLOCKLIST`)
  - Amount above hard limit or velocity above hard cap -> `PENDING_REVIEW` (`HARD_RULE_LIMIT`)
- **Step 1:** ML timeout, connection error, circuit breaker open -> `PENDING_REVIEW` (`ML_UNAVAILABLE`)
- **Step 2:** ML response invalid -> `PENDING_REVIEW` (`ML_INVALID_RESPONSE`)
- **Step 3:** $\text{uncertainty} > \text{uncertainty\_threshold}$ ($0.30$) -> `PENDING_REVIEW` (`HIGH_UNCERTAINTY`)
- **Step 4:** $\text{fraud\_probability} \ge \text{reject\_threshold}$ ($0.90$) -> `REJECTED` (`HIGH_FRAUD_PROBABILITY`)
- **Step 5:** $\text{fraud\_probability} \le \text{approve\_threshold}$ ($0.10$) -> `APPROVED` (`LOW_FRAUD_PROBABILITY`)
- **Step 6:** Otherwise ($0.10 < P < 0.90$) -> `PENDING_REVIEW` (`MID_BAND`)

---

## 11. Transaction Lifecycle & State Machine

```
               [risk decision approve]
              ┌────────────────────────> APPROVED
              │
              │[risk decision reject]
              ├────────────────────────> REJECTED
              │
CREATED ──────┤[risk decision review]
              └────────────────────────> PENDING_REVIEW ──┬──[analyst approves]──> REVIEW_APPROVED
                                                          │
                                                          └──[analyst rejects]───> REVIEW_REJECTED
```

---

## 13. Event Architecture (Transactional Outbox)
- Producing service writes `outbox_events` in the same DB transaction as entity updates.
- Relay polls `outbox_events` (`FOR UPDATE SKIP LOCKED`) and calls `POST /internal/events` on subscribers.
- Subscribers record `event_id` in `processed_events` for at-least-once deduplication.

---

## 14 & 15. ML Architecture & FastAPI Serving
- **Dual models:** `purchase-fraud-model` and `transfer-fraud-model`.
- **FastAPI Endpoints:**
  - `POST /predict/purchase` & `POST /predict/transfer`
  - `POST /explain/purchase` & `POST /explain/transfer` (SHAP on-demand for analyst)
  - `GET /models` & `POST /admin/reload/{family}` (Hot reload on champion promotion)
- **Champion Aliasing:** MLflow `@champion` alias resolution.

---

## 16. Database Schemas (PostgreSQL)
- `auth`: `users`, `refresh_tokens`, `login_attempts`
- `customer`: `customers`, `customer_preferences`, `beneficiaries`
- `merchant`: `merchants`, `merchant_api_keys`
- `transaction`: `payment_sessions`, `transactions`, `payments`, `transfers`, `wallets`, `wallet_ledger`, `transaction_status_history`, `idempotency_keys`, `webhook_deliveries`
- `risk`: `risk_evaluations`, `decision_policies`, `customer_activity`
- `review`: `review_cases`, `review_decisions`, `review_notes`
- `notification`: `notifications`, `notification_deliveries`
- `audit`: `audit_events` (hash-chained with `prev_hash` & `row_hash`)
- `outbox_events` and `processed_events` in every service schema.

---

## 17 & 18. API Reference & Error Conventions
- RFC 7807 Error Envelope (`type`, `code`, `title`, `status`, `detail`, `trace_id`, `errors`).
- Public endpoints under `/api/v1/...`, internal under `/internal/...`.
- `Idempotency-Key` mandatory on payment sessions, checkout confirm, and transfers.
- `If-Match: <version>` on analyst case decisions.

---

## 19 to 30. Security, Resilience, Observability, K8s & Roadmap
- Resilience4j circuit breakers, timeouts (1500ms ML timeout), bulkheads, recovery path for asynchronous risk evaluation.
- Metrics, PromQL alerts, Grafana dashboards, hash chain audit verification.
- Milestones M0 through M7.

