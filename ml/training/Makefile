# ==============================================================================
# Vigilia Platform — ML Training Makefile (Member 1)
# Each target maps to one stage of the reproducible pipeline (§13 of design v5).
# All stages can run individually or together via `make all`.
#
# Usage:
#   make all         — full pipeline from data check to model registration
#   make tune        — Optuna hyperparameter search only
#   make compare     — repeated CV comparison only
#   make stats       — statistical tests only (Friedman / Wilcoxon / Holm)
#   make select      — apply the pre-committed selection rule
#   make calibrate   — calibrate all models on part B
#   make evaluate    — one-time evaluation on fraudTest
#   make register    — register champion model in MLflow
#   make test        — run unit and smoke tests
#   make lint        — ruff check + format
#   make clean       — remove generated reports and cached files
# ==============================================================================

PYTHON := python
SRC    := -m src

.PHONY: all tune compare stats select calibrate evaluate register test lint clean

# Full reproducible pipeline (§13)
all:
	$(PYTHON) $(SRC).pipeline all

# Stage: Optuna hyperparameter tuning on tuning subsample of A (§6)
tune:
	$(PYTHON) $(SRC).pipeline tune

# Stage: Repeated stratified CV + grouped CV comparison on comparison subsample of A (§4, §5)
compare:
	$(PYTHON) $(SRC).pipeline compare

# Stage: Friedman / Wilcoxon / Holm / CIs / effect sizes (§9)
stats:
	$(PYTHON) $(SRC).pipeline stats

# Stage: Apply the pre-committed selection rule (§7) — must run after stats
select:
	$(PYTHON) $(SRC).pipeline select

# Stage: Calibrate all models on part B, threshold on part C (§10)
calibrate:
	$(PYTHON) $(SRC).pipeline calibrate

# Stage: One-time evaluation on fraudTest — run ONCE at the very end (§8)
evaluate:
	$(PYTHON) $(SRC).pipeline evaluate

# Stage: Export artifacts and register champion in MLflow (§11, §12)
register:
	$(PYTHON) $(SRC).pipeline register

# Tests: unit tests + smoke test (§14)
test:
	$(PYTHON) -m pytest tests/ -v
	$(PYTHON) smoke_test_mlflow.py

# Lint and format (ruff)
lint:
	$(PYTHON) -m ruff check src/ tests/
	$(PYTHON) -m ruff format --check src/ tests/

# Remove generated outputs (not data, not MLflow runs)
clean:
	rm -rf reports/figures/* reports/tables/*
	find . -type d -name __pycache__ -exec rm -rf {} +
	find . -name "*.pyc" -delete
