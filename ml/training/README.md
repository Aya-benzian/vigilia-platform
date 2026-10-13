# ML Training Workspace (Member 1)

This directory is the dedicated development and experiment workspace for **Member 1 (Data, Training, Evaluation, Calibration, Statistics, and MLflow)**.

---

## Architecture: Training vs. Serving

> [!NOTE]
> **`ml-training` is a batch training/development environment, NOT a continuous runtime microservice.**  
> - It is **not** included in `docker-compose.yml` because it does not run as a 24/7 service.
> - **Execution model:** `Container starts -> executes training script -> produces model artifact in artifacts/ -> container exits`.
> - In contrast, `ml-serving/` is a separate runtime service that exposes a continuous FastAPI prediction API.

---

## Directory Structure

```text
ml/training/
├── Dockerfile           # Multi-stage, non-root Python 3.12 training image
├── .dockerignore        # Excludes virtual environments, caches, datasets, and artifacts
├── requirements.txt     # Baseline ML & data-science dependencies
├── README.md            # Workspace documentation and usage instructions
├── data/                # Local datasets (gitignored; mount at runtime)
├── src/                 # Member 1 training, preprocessing, and validation source code
└── artifacts/           # Trained model binaries and metrics output (gitignored; mount at runtime)
```

---

## Quickstart Guide

Member 1 does **not** need to install Python, pip, or ML dependencies on their host machine. All execution is handled inside the Docker container.

### 1. Build the Training Image

From the repository root:

```bash
docker build -t vigilia-ml-training ml/training
```

### 2. Run Scripts with Live Volume Mounts

Mounting the local directories allows you to edit Python files in your host IDE (e.g., VS Code) while executing inside the container with immediate effect:

```bash
# Run a specific training or evaluation script
docker run --rm \
  -v $(pwd)/ml/training/src:/workspace/src \
  -v $(pwd)/ml/training/data:/workspace/data \
  -v $(pwd)/ml/training/artifacts:/workspace/artifacts \
  vigilia-ml-training python src/main.py
```

### 3. Open an Interactive Container Shell

To run interactive one-off commands, inspect datasets, or test scripts:

```bash
docker run --rm -it \
  -v $(pwd)/ml/training/src:/workspace/src \
  -v $(pwd)/ml/training/data:/workspace/data \
  -v $(pwd)/ml/training/artifacts:/workspace/artifacts \
  vigilia-ml-training bash
```

---

## Managing Dependencies

The baseline environment includes:
- `numpy` & `pandas`: Data ingestion and feature manipulation
- `scipy`: Statistical testing (Friedman/Wilcoxon, Holm)
- `scikit-learn`: Benchmark models, metrics, and calibration
- `mlflow`: Experiment tracking and model artifact packaging

### Adding or Updating Packages:
1. Edit `ml/training/requirements.txt` to add or update desired libraries.
2. **Rebuild the image** after any change to `requirements.txt`:
   ```bash
   docker build -t vigilia-ml-training ml/training
   ```

---

## MLflow Tracking & Model Registry Integration

The Vigilia platform provides an MLflow Tracking Server backed by PostgreSQL metadata storage and persistent artifact proxying.

### Tracking URIs
* **From inside Docker containers (`vigilia_default` network)**:
  ```bash
  export MLFLOW_TRACKING_URI="http://mlflow:5000"
  ```
* **From the host machine / local terminal**:
  ```bash
  export MLFLOW_TRACKING_URI="http://localhost:5000"
  ```
* **Web UI**: Open `http://localhost:5000` in your browser.

### Verifying Connection (Smoke Test)
A self-contained smoke test (`smoke_test_mlflow.py`) validates experiment creation, metric/parameter logging, artifact proxying, and registering models with the `@champion` alias:

```bash
# Run from inside Docker connected to Compose network:
docker run --rm \
  --network vigilia_default \
  -e MLFLOW_TRACKING_URI=http://mlflow:5000 \
  vigilia-ml-training python smoke_test_mlflow.py

# Or run from your host machine (if virtual environment is active):
export MLFLOW_TRACKING_URI=http://localhost:5000
python ml/training/smoke_test_mlflow.py
```

### Logging & Registering the Champion Model in Python
In your training scripts (`ml/training/src/`), use the following pattern:

```python
import os
import mlflow
from mlflow.tracking import MlflowClient

# 1. Connect to tracking server
mlflow.set_tracking_uri(os.getenv("MLFLOW_TRACKING_URI", "http://mlflow:5000"))
mlflow.set_experiment("vigilia-fraud-detection")

with mlflow.start_run(run_name="model_run_1"):
    # Log hyperparameters and metrics
    mlflow.log_params({"model_type": "xgboost", "max_depth": 6})
    mlflow.log_metrics({"f1_score": 0.942, "auc_roc": 0.985})
    
    # Log model artifacts to Model Registry
    model_name = "vigilia_fraud_detector"
    mlflow.sklearn.log_model(
        sk_model=fitted_pipeline,
        artifact_path="model",
        registered_model_name=model_name
    )

# 2. Promote model version to 'champion' alias
client = MlflowClient()
registered_model = client.get_registered_model(model_name)
latest_version = registered_model.latest_versions[-1].version
client.set_registered_model_alias(name=model_name, alias="champion", version=latest_version)
print(f"Version {latest_version} promoted to @champion")
```

