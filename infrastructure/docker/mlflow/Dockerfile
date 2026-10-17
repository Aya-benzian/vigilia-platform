FROM ghcr.io/mlflow/mlflow:v3.17.1

# Install PostgreSQL adapter for MLflow tracking metadata store
RUN pip install --no-cache-dir psycopg2-binary==2.9.9

# Create dedicated non-root user and persistent artifact directory for DevSecOps compliance
RUN addgroup --system mlflow && adduser --system --group mlflow && \
    mkdir -p /mlflow/artifacts && \
    chown -R mlflow:mlflow /mlflow

USER mlflow
WORKDIR /mlflow

EXPOSE 5000
