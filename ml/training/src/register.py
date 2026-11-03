"""
register.py — MLflow model registration (Member 1, §13).

Registers the calibrated champion pipeline in MLflow Model Registry with:
    - serialization_format="cloudpickle" (required for XGBoost + CalibratedClassifierCV)
    - @champion alias
    - model_card.json artifact
    - feature_spec.json artifact (Contract 1 with Member 2)

Contract 10: The @champion alias is what Members 2 and 3 load at serving time.
"""

import json
import logging
from pathlib import Path

import mlflow
import mlflow.sklearn
from mlflow import MlflowClient

from config import (
    MLFLOW_TRACKING_URI,
    MLFLOW_MODEL_NAME,
    MLFLOW_CHAMPION_ALIAS,
    ARTIFACTS_DIR,
    SEED,
)
from preprocess import export_feature_spec

logger = logging.getLogger(__name__)


# ==============================================================================
# Model card
# ==============================================================================

def build_model_card(metrics: dict, threshold: float, model_name: str) -> dict:
    """
    Build model_card.json — human-readable summary of the champion model.

    Args:
        metrics: Dict from evaluate.compute_metrics.
        threshold: Operating threshold.
        model_name: E.g. "xgboost".

    Returns:
        model_card dict (also saved to ARTIFACTS_DIR/model_card.json).
    """
    card = {
        "schema_version": "1.0",
        "model_name": model_name,
        "mlflow_registered_name": MLFLOW_MODEL_NAME,
        "mlflow_alias": MLFLOW_CHAMPION_ALIAS,
        "dataset": "Sparkov synthetic credit card fraud dataset",
        "random_seed": SEED,
        "operating_threshold": threshold,
        "performance": {
            "pr_auc":        metrics.get("pr_auc"),
            "roc_auc":       metrics.get("roc_auc"),
            "f1":            metrics.get("f1"),
            "f2":            metrics.get("f2"),
            "mcc":           metrics.get("mcc"),
            "brier":         metrics.get("brier"),
            "inference_ms":  metrics.get("inference_ms_per_sample"),
        },
        "confusion_matrix": {
            "tp": metrics.get("tp"),
            "fp": metrics.get("fp"),
            "tn": metrics.get("tn"),
            "fn": metrics.get("fn"),
        },
        "business_cost":  metrics.get("business_cost"),
        "intended_use":   "Fraud/risk classification for FinTech transactions.",
        "limitations": [
            "Trained on synthetic Sparkov data — performance may differ on real data.",
            "Threshold tuned on part C of training data — may need recalibration in production.",
        ],
        "fairness_note": "No fairness analysis performed in Phase 1.",
    }

    card_path = ARTIFACTS_DIR / "model_card.json"
    card_path.parent.mkdir(parents=True, exist_ok=True)
    with open(card_path, "w") as f:
        json.dump(card, f, indent=2)

    logger.info(f"Model card saved to {card_path}")
    return card


# ==============================================================================
# Registration
# ==============================================================================

def register_champion(
    calibrated_pipeline,
    metrics: dict,
    threshold: float,
    model_type: str = "xgboost",
) -> str:
    """
    Register the calibrated champion pipeline in MLflow Model Registry.

    Steps:
        1. Log model with cloudpickle (required for XGBoost + CalibratedClassifierCV).
        2. Register under MLFLOW_MODEL_NAME.
        3. Set @champion alias.
        4. Log model_card.json and feature_spec.json as artifacts.
        5. Log threshold as a parameter.

    Args:
        calibrated_pipeline: Calibrated champion pipeline.
        metrics: Dict from evaluate.compute_metrics (test set metrics).
        threshold: Operating threshold.
        model_type: Model name string for the card.

    Returns:
        Model version string (e.g. "1").
    """
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
    client = MlflowClient()

    # Build artifacts
    card = build_model_card(metrics, threshold, model_type)
    spec = export_feature_spec()

    card_path = ARTIFACTS_DIR / "model_card.json"
    spec_path = ARTIFACTS_DIR / "feature_spec.json"

    with mlflow.start_run(run_name=f"register_{model_type}_champion") as run:
        # Log key metrics
        for k, v in metrics.items():
            if isinstance(v, (int, float)):
                mlflow.log_metric(k, v)

        mlflow.log_param("model_type",  model_type)
        mlflow.log_param("threshold",   threshold)
        mlflow.log_param("seed",        SEED)

        # Log artifacts
        mlflow.log_artifact(str(card_path), artifact_path="metadata")
        mlflow.log_artifact(str(spec_path), artifact_path="metadata")

        # Save model locally with cloudpickle then log as artifact
        # (avoids MLflow 3.x client CreateLoggedModel incompatibility with MLflow 2.14 server)
        import tempfile, shutil
        tmp_dir = tempfile.mkdtemp()
        model_local_path = Path(tmp_dir) / "model"
        mlflow.sklearn.save_model(
            sk_model=calibrated_pipeline,
            path=str(model_local_path),
            serialization_format=mlflow.sklearn.SERIALIZATION_FORMAT_CLOUDPICKLE,
        )
        mlflow.log_artifacts(str(model_local_path), artifact_path="model")
        shutil.rmtree(tmp_dir, ignore_errors=True)

        run_id = run.info.run_id

    # Register model in Model Registry from run artifact
    model_version_obj = mlflow.register_model(
        model_uri=f"runs:/{run_id}/model",
        name=MLFLOW_MODEL_NAME,
    )
    latest_version = str(model_version_obj.version)

    # Set @champion alias
    client.set_registered_model_alias(
        name=MLFLOW_MODEL_NAME,
        alias=MLFLOW_CHAMPION_ALIAS,
        version=latest_version,
    )

    logger.info(
        f"Model registered: name='{MLFLOW_MODEL_NAME}' "
        f"version={latest_version} "
        f"alias='{MLFLOW_CHAMPION_ALIAS}'"
    )
    logger.info(
        f"Members 2/3 can load with: "
        f"mlflow.sklearn.load_model('models:/{MLFLOW_MODEL_NAME}@{MLFLOW_CHAMPION_ALIAS}')"
    )

    return latest_version
