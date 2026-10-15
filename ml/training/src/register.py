"""
register.py — MLflow model registration for Member 1 (§12 of design v5).

Registers the final calibrated Pipeline in the MLflow Model Registry as:
    registered name : "fraud-model"    (MLFLOW_MODEL_NAME in config.py)
    alias           : "champion"        (MLFLOW_MODEL_ALIAS in config.py)
    version tag     : "fraud-model-v1" (Contract 10 with Members 2 and 3)

Critical requirements (from design v5 §3 and §12):
    - Use serialization_format="cloudpickle" — the default skops format
      REJECTS CalibratedClassifierCV + XGBClassifier as untrusted types.
    - Log with model signature and input example so Member 2 can validate.
    - The model must load in a fresh environment using ONLY requirements.lock.txt.
    - Base (uncalibrated) model is also registered separately for SHAP (Member 2).

MLflow experiments:
    - Final run: MLFLOW_EXP_FINAL
    - Log: parameters, metrics, data hash, library versions, seed, git commit,
           figures and tables as artifacts, model with signature and input example.
    - Tags: stage="final", dataset="sparkov"
"""

import logging
import os
from typing import Any, Dict, Optional

import mlflow
import mlflow.sklearn
from mlflow.tracking import MlflowClient
from mlflow.models import infer_signature

from config import (
    SEED,
    MLFLOW_TRACKING_URI,
    MLFLOW_EXP_FINAL,
    MLFLOW_MODEL_NAME,
    MLFLOW_MODEL_ALIAS,
)

logger = logging.getLogger(__name__)


def connect_mlflow(tracking_uri: Optional[str] = None) -> MlflowClient:
    """
    Connect to the MLflow Tracking Server and return a client.

    Uses tracking_uri if provided, otherwise falls back to MLFLOW_TRACKING_URI
    from config.py (overridable via MLFLOW_TRACKING_URI environment variable).

    Args:
        tracking_uri: Optional override URI.

    Returns:
        Configured MlflowClient.
    """
    raise NotImplementedError


def log_final_run(
    calibrated_pipeline: Any,
    base_pipeline: Any,
    params: Dict,
    metrics: Dict,
    data_hash_train: str,
    data_hash_test: str,
    artifact_paths: Dict,
    X_example,
    y_example,
) -> str:
    """
    Log the final training run to MLflow and register both models.

    Steps:
        1. Set experiment to MLFLOW_EXP_FINAL.
        2. Start run with tags: stage="final", dataset="sparkov".
        3. Log params (hyperparameters, seed, split ratios, calibration method, threshold).
        4. Log metrics (all test metrics, Brier before/after, inference timing).
        5. Log data_hash_train and data_hash_test as params.
        6. Log library versions as params (mlflow, sklearn, xgboost, numpy, pandas).
        7. Log git commit hash as a tag (if available).
        8. Log all figures and tables as artifacts.
        9. Log calibrated pipeline with:
               mlflow.sklearn.log_model(
                   sk_model=calibrated_pipeline,
                   artifact_path="model",
                   registered_model_name=MLFLOW_MODEL_NAME,
                   signature=infer_signature(X_example, y_example),
                   input_example=X_example.iloc[:3],
                   serialization_format="cloudpickle",   # REQUIRED
               )
        10. Log base pipeline separately as "model_base" (for SHAP — Member 2).
        11. Set alias "champion" on the registered version.
        12. Add tag fraud_model_version = "fraud-model-v1" (Contract 10).

    Args:
        calibrated_pipeline: Final calibrated Pipeline.
        base_pipeline: Final base (uncalibrated) Pipeline.
        params: Hyperparameters and configuration to log.
        metrics: Test metrics to log.
        data_hash_train: SHA-256 of fraudTrain.csv.
        data_hash_test: SHA-256 of fraudTest.csv.
        artifact_paths: Dict of local paths to figures and tables.
        X_example: Small sample of features for signature inference.
        y_example: Corresponding labels.

    Returns:
        MLflow run ID (str).
    """
    raise NotImplementedError


def promote_to_champion(model_name: str, version: str, client: MlflowClient) -> None:
    """
    Set the 'champion' alias on the given registered model version.

    Only one version can hold the 'champion' alias at a time.
    The previous champion alias is automatically moved.

    Args:
        model_name: Registered model name (MLFLOW_MODEL_NAME).
        version: The version string to promote.
        client: MlflowClient connected to the tracking server.
    """
    raise NotImplementedError


def verify_model_loads(model_name: str, alias: str = "champion") -> bool:
    """
    Smoke test: load the registered model by alias and run a dummy prediction.

    This verifies that Member 2 can load the model from a fresh environment.
    Must pass before declaring the pipeline complete.

    Args:
        model_name: Registered model name.
        alias: Alias to resolve (default "champion").

    Returns:
        True if the model loads and predicts successfully.

    Raises:
        AssertionError: If prediction output is invalid.
    """
    raise NotImplementedError
