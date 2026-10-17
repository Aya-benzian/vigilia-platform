# Stage 1: Build dependencies in an isolated virtual environment
FROM python:3.12-slim-bookworm AS builder

WORKDIR /build

# Create dedicated virtual environment
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
# Install from pinned requirements first; lock file used in CI/CD
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Stage 2: Clean training execution image
FROM python:3.12-slim-bookworm

WORKDIR /workspace

# Create non-root user for safe execution
RUN addgroup --system appgroup && adduser --system --group appuser

# Copy pre-built virtual environment from builder stage
COPY --from=builder /opt/venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    GIT_PYTHON_REFRESH=quiet \
    PYTHONPATH="/workspace:/workspace/src" \
    # Default MLflow tracking URI — override at runtime for Docker network
    MLFLOW_TRACKING_URI="http://mlflow:5000" \
    MLFLOW_EXPERIMENT_NAME="vigilia-fraud-detection"

# Copy training workspace files and configure ownership
COPY --chown=appuser:appgroup . /workspace

USER appuser

# Default: run the full reproducible pipeline (override for individual stages)
# Usage examples:
#   docker run vigilia-ml-training                          # full pipeline
#   docker run vigilia-ml-training python -m src.pipeline tune
#   docker run vigilia-ml-training python smoke_test_mlflow.py
CMD ["python", "-m", "src.pipeline"]
