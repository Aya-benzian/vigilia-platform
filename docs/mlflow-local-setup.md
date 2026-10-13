# Local MLflow Environment Setup — Vigilia M5 Infrastructure

**Owner:** Member 5 (DevSecOps, Infrastructure & MLOps)  
**Status:** Implemented & Verified (Local Docker Foundation)  
**Date:** October 13, 2026  

---

## 1. Overview & Architecture

Vigilia uses **MLflow Tracking Server and Model Registry** as the bridge between model research/training (**Member 1**) and real-time model serving (**Member 2**).

### High-Level Topology & Data Flow

```text
       +---------------------------------------------+
       |           M1: Training Workspace            |
       |       (Batch Container / Local Shell)       |
       +---------------------------------------------+
                              |
                   Log Runs & Register Model
                   (HTTP REST API: Port 5000)
                              |
                              v
       +---------------------------------------------+
       |         MLflow Tracking Server              |
       |          (vigilia-mlflow:5000)              |
       |  - Mode: --serve-artifacts (HTTP Proxy)     |
       +---------------------------------------------+
                 /                         \
       Metadata (SQL)                  Artifacts (Files)
               /                             \
              v                               v
+-----------------------------+   +-----------------------------+
|    PostgreSQL Database      |   |   Named Docker Volume       |
|  - DB: 'mlflow'             |   |   'mlflow_artifacts'        |
|  - User: 'mlflow'           |   |   mapped to:                |
|  - Container: postgres:5432 |   |   /mlflow/artifacts         |
+-----------------------------+   +-----------------------------+
                              ^
                              |
                  Fetch Model via HTTP Proxy
                  (models:/<model_name>@champion)
                              |
       +---------------------------------------------+
       |             M2: ML Serving API              |
       |      (FastAPI container: ml-serving:8000)   |
       |  - Loads @champion model at startup cache   |
       |  - Evaluates in-memory for zero network lag |
       +---------------------------------------------+
```

### Key Architectural Decisions

1. **Database-Level Isolation**:
   - MLflow stores experiment runs, parameters, metrics, and model registry metadata in a dedicated PostgreSQL database named `mlflow` with a dedicated user `mlflow`.
   - The application database `vigilia` remains completely decoupled and safe from migration alterations or schema collision.
2. **Artifact Proxying (`--serve-artifacts`)**:
   - MLflow is configured with `--serve-artifacts --artifacts-destination /mlflow/artifacts`.
   - Clients in other containers (`ml-training`, `ml-serving`) interact with artifacts strictly over HTTP (`mlflow-artifacts:/...`).
   - Neither M1 nor M2 needs shared disk mounts or database access.
3. **Decoupled Model Serving**:
   - The serving service loads the `@champion` model into memory during startup.
   - Predictions (`/predict`) are evaluated in-memory with zero runtime dependence on MLflow availability.
4. **DevSecOps Hardening**:
   - Pinned image `ghcr.io/mlflow/mlflow:v2.14.1`.
   - Runs as non-root user `mlflow` (`uid/gid: 1000`).
   - Internal Docker network (`vigilia_default`) with explicit container healthcheck.

---

## 2. Ports, URLs & Environment Variables

### Endpoints
* **Host Browser / Local UI**: `http://localhost:5000`
* **Internal Docker Service URL**: `http://mlflow:5000`
* **Health Check Probe**: `http://mlflow:5000/health` (HTTP 200)

### Environment Variables (`.env.example`)

| Variable | Default Value | Scope | Description |
|---|---|---|---|
| `MLFLOW_PORT` | `5000` | Host / Docker Compose | Port exposed on host for MLflow UI |
| `MLFLOW_DB_USER` | `mlflow` | Internal (Postgres/MLflow) | Least-privilege PostgreSQL user |
| `MLFLOW_DB_PASSWORD` | `mlflow_secret` | Internal (Postgres/MLflow) | Password for MLflow database user |
| `MLFLOW_DB_NAME` | `mlflow` | Internal (Postgres/MLflow) | Dedicated database for MLflow |
| `MLFLOW_TRACKING_URI` | `http://mlflow:5000` (Docker) / `http://localhost:5000` (Host) | M1 & M2 | Tracking server connection URI |
| `MLFLOW_EXPERIMENT_NAME` | `vigilia-fraud-detection` | M1 | Default experiment namespace |

---

## 3. Starting & Stopping the MLflow Stack

MLflow is fully integrated into `docker-compose.yml`:

```bash
# Start MLflow along with its PostgreSQL dependency
docker compose up -d mlflow

# Start the entire Vigilia platform (all 14 containers)
docker compose up -d

# Check status and health
docker compose ps mlflow

# Inspect MLflow server logs
docker compose logs -f mlflow

# Stop MLflow
docker compose stop mlflow
```

---

## 4. Member 1 (ML Training) Integration Guide

Member 1 trains models batch-style inside `ml/training/`.

### 4.1 Running the Infrastructure Smoke Test
M5 has provided a verified smoke test script (`ml/training/smoke_test_mlflow.py`) that tests:
1. Connecting to the tracking server.
2. Creating/selecting the experiment.
3. Logging test metrics, parameters, and an artifact file.
4. Uploading and downloading an artifact via HTTP proxy.
5. Registering a dummy model and assigning the `@champion` alias.

Execute the smoke test inside the container:
```bash
docker run --rm \
  --network vigilia_default \
  -e MLFLOW_TRACKING_URI=http://mlflow:5000 \
  vigilia-ml-training python smoke_test_mlflow.py
```

### 4.2 Logging Real Models & Registering Champions
In training scripts (`ml/training/src/train.py`), M1 should use the following workflow:

```python
import os
import mlflow
from mlflow.tracking import MlflowClient

# 1. Connect
mlflow.set_tracking_uri(os.getenv("MLFLOW_TRACKING_URI", "http://mlflow:5000"))
mlflow.set_experiment("vigilia-fraud-detection")

with mlflow.start_run(run_name="xgboost_candidate_v1"):
    # Log hyperparameters & metrics
    mlflow.log_params({"model_type": "xgboost", "max_depth": 6, "learning_rate": 0.05})
    mlflow.log_metrics({"f1_score": 0.942, "auc_roc": 0.985, "pr_auc": 0.921})

    # Log model to Model Registry
    model_name = "vigilia_fraud_detector"
    mlflow.sklearn.log_model(
        sk_model=fitted_pipeline,
        artifact_path="model",
        registered_model_name=model_name
    )

# 2. Promote to Champion
client = MlflowClient()
registered_model = client.get_registered_model(model_name)
latest_version = registered_model.latest_versions[-1].version
client.set_registered_model_alias(name=model_name, alias="champion", version=latest_version)
print(f"Model '{model_name}' version {latest_version} is now @champion")
```

---

## 5. Member 2 (ML Serving) Integration Guide

Member 2 exposes real-time inference via FastAPI in `ml/serving/`.

### 5.1 Standalone Model Loader
M5 has provided `ml/serving/model_loader.py` demonstrating non-blocking retrieval and startup caching:

```python
from model_loader import load_champion_model, get_loaded_model

# In FastAPI lifespan or startup event:
@app.on_event("startup")
def startup_event():
    try:
        model, version = load_champion_model(
            model_name="vigilia_fraud_detector",
            alias="champion"
        )
        logger.info(f"Loaded champion model version {version}")
    except Exception as e:
        logger.error(f"Could not load champion model: {e}")
        # Mark readiness probe down (/health/ready -> 503) or prepare fallback heuristics
```

### 5.2 Standalone Verification Command
To verify that M2's serving container can reach MLflow and resolve `@champion`:
```bash
docker run --rm \
  --network vigilia_default \
  -e MLFLOW_TRACKING_URI=http://mlflow:5000 \
  -e ML_MODEL_NAME=smoke_test_champion_model \
  vigilia-ml-serving python model_loader.py
```

---

## 6. Persistence & Storage Verification

* **Metadata**: Persisted in PostgreSQL named volume `postgres_data`. Even if `vigilia-mlflow` container is destroyed and recreated, all runs, experiments, and model versions remain intact.
* **Artifacts**: Persisted in Docker named volume `mlflow_artifacts` mounted at `/mlflow/artifacts`.
* **Verification performed**:
  - Executed `docker compose restart mlflow`.
  - Re-queried model registry and downloaded artifacts from `ml-serving`.
  - Verified 100% data persistence with zero loss.

---

## 7. Troubleshooting Common Issues

1. **`Connection refused` on `http://mlflow:5000`**:
   - Cause: Container not attached to `vigilia_default` network, or `mlflow` service not running.
   - Fix: Ensure `docker compose ps mlflow` reports `healthy`. Run container with `--network vigilia_default`.
2. **`ModuleNotFoundError: No module named 'psycopg2'`**:
   - Cause: Using uncustomized `ghcr.io/mlflow/mlflow` base image.
   - Fix: Always build via `docker compose build mlflow` which installs `psycopg2-binary`.
3. **`pkg_resources` missing in Python 3.12**:
   - Cause: `setuptools >= 70.0.0` removed `pkg_resources`.
   - Fix: Keep `setuptools<70.0.0` pinned in `ml/training/requirements.txt` and `ml/serving/requirements.txt`.
4. **`RestException: RESOURCE_DOES_NOT_EXIST` for `@champion`**:
   - Cause: No model version has been assigned the `champion` alias yet.
   - Fix: Run `python smoke_test_mlflow.py` or have M1 assign the alias via `client.set_registered_model_alias()`.

---

## 8. Missing Contract Details (Handoff between M1 and M2)

Before production model serving can be finalized, Member 1 and Member 2 must formalize a contract under `contracts/ml/`:

1. **Feature Specification (`feature_spec.json`)**:
   - List of expected input features, types (float, int, string), categorical encodings, and missing-value imputation strategy.
2. **Model Bundle Directory Structure**:
   - The final model bundle must package more than the raw estimator:
     - Preprocessing pipeline (`preprocessor.joblib`)
     - Calibrated estimator (`calibrator.joblib` or Platt scaling parameters)
     - Dimensionality reduction artifacts (`pca_anchor.json` / UMAP coordinates)
     - Model card metadata (`model_card.json`)
3. **Uncertainty Score Semantics**:
   - Definition of threshold ranges for `/predict`: e.g. uncertainty $> 0.40 \implies \text{flag for manual review}$.

---

## 9. Future Kubernetes Migration Path (M5 Roadmap)

When migrating from Docker Compose to Kubernetes:
1. **Metadata Store**:
   - Point `MLFLOW_BACKEND_STORE_URI` to a managed PostgreSQL cluster (e.g. AWS RDS / Cloud SQL) or a Kubernetes StatefulSet with persistent PVC.
   - Pass credentials securely via Kubernetes `Secret`.
2. **Artifact Store**:
   - Replace local volume with an S3 / MinIO / GCS bucket:
     ```text
     --artifacts-destination s3://vigilia-mlflow-artifacts/
     ```
   - Provide AWS IAM Role for Service Accounts (IRSA) or GCP Workload Identity to the MLflow Deployment pod.
3. **Clients**:
   - `ml-training` CronJob/Job and `ml-serving` Deployment will connect to `http://mlflow-service.mlflow.svc.cluster.local:5000`.
   - Because we chose `--serve-artifacts` HTTP proxying, training and serving pods **never need direct S3/storage credentials or IAM permissions**. All artifact streaming continues to happen over HTTP via MLflow.
