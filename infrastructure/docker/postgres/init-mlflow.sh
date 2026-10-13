#!/bin/bash
set -e

# Idempotently create dedicated role and database for MLflow Tracking Server
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${MLFLOW_DB_USER:-mlflow}') THEN
            CREATE ROLE ${MLFLOW_DB_USER:-mlflow} LOGIN PASSWORD '${MLFLOW_DB_PASSWORD:-mlflow_secret}';
        END IF;
    END
    \$\$;

    SELECT 'CREATE DATABASE ${MLFLOW_DB_NAME:-mlflow} OWNER ${MLFLOW_DB_USER:-mlflow}'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${MLFLOW_DB_NAME:-mlflow}')\gexec

    GRANT ALL PRIVILEGES ON DATABASE ${MLFLOW_DB_NAME:-mlflow} TO ${MLFLOW_DB_USER:-mlflow};
EOSQL
