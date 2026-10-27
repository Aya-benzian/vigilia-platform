# ML Serving Service — Member 2 (Vigilia Platform)

Production ML model serving microservice built on FastAPI, exposing real-time transaction scoring, calibrated probability, predictive uncertainty, and explainability.

---

## Milestone 1 Implementation

- **Configuration:** Managed via `config.py`. Enforces dummy-model protection (strictly forbidden in `production` and `staging`).
- **Input Validation (Contract 1):** Strict Pydantic v2 schemas in `schemas.py` rejecting all extra fields (`cc_num`, `trans_num`, etc.) with HTTP 422.
- **Background Model Loader:** Asynchronous daemon thread in `model_loader.py` with bounded exponential backoff (`MODEL_LOAD_INITIAL_BACKOFF_SEC` to `MODEL_LOAD_MAX_BACKOFF_SEC`).
- **Health Probes:**
  - `GET /health/live`: Returns HTTP 200 immediately to prevent premature Kubernetes pod terminations.
  - `GET /health/ready`: Returns HTTP 503 until model is loaded, then HTTP 200.
  - `GET /health/startup`: Returns HTTP 503 until initial model loading is complete, then HTTP 200.
- **Observability:** Prometheus metrics at `/metrics` with `fintech_ml_prediction_uncertainty` gauge.

---

## Configuration Reference

| Environment Variable | Default | Description |
| :--- | :--- | :--- |
| `APP_ENV` | `local` | Environment mode (`local`, `test`, `staging`, `production`). |
| `PORT` | `8000` | Port to bind. |
| `MLFLOW_TRACKING_URI` | `http://mlflow:5000` | MLflow tracking server HTTP endpoint. |
| `ML_MODEL_NAME` | `fraud-model` | Registered model name in MLflow. |
| `ML_MODEL_ALIAS` | `champion` | Model alias pointing to champion model version. |
| `ML_DECISION_THRESHOLD` | `0.1948` | Calibrated decision threshold tuned on Part C. |
| `USE_DUMMY_MODEL` | `false` | Enable in-memory dummy model (only permitted in `local`/`test`). |
| `MLFLOW_HTTP_REQUEST_MAX_RETRIES` | `1` | Fast-fail retry count for MLflow REST requests. |
| `MLFLOW_HTTP_REQUEST_TIMEOUT` | `3` | Timeout in seconds for MLflow HTTP requests. |
| `MODEL_LOAD_INITIAL_BACKOFF_SEC` | `1.0` | Initial retry backoff interval in seconds. |
| `MODEL_LOAD_MAX_BACKOFF_SEC` | `30.0` | Maximum retry backoff cap in seconds. |

---

## Testing

Install test dependencies:
```bash
pip install -r requirements-dev.txt
```

Run tests:
```bash
pytest ml/serving/tests/ -v
```
