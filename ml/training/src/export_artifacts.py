"""
export_artifacts.py — Hand-off artifacts for Members 2, 3, and 4 (§11 of design v5).

This module produces everything that other members need from Member 1.
It is run at the end of the pipeline, after model selection and evaluation.

Artifacts produced:
    1. artifacts/test_predictions.parquet
       Columns: all features, is_fraud, calibrated_score, prediction, for fraudTest.
       Member 2 uses this to run PCA / t-SNE / UMAP visualizations.

    2. artifacts/feature_spec.json
       Machine-readable Contract 1 — raw input field names, types, ranges, order.
       Member 2 imports this to validate incoming requests.

    3. artifacts/model_card.json
       Full documentation of the final model (§15 of design v5).

    4. artifacts/scores_part_b.parquet
       Calibrated scores on part B. For Member 2's conformal prediction.

    5. artifacts/scores_part_c.parquet
       Calibrated scores on part C + threshold used. For Member 2 and Member 3.

    6. Base (uncalibrated) model saved separately in MLflow.
       For Member 2's SHAP explanations — SHAP explains the base model,
       not the calibrated wrapper.

All artifacts are also logged to MLflow under MLFLOW_EXP_FINAL.
"""

import json
import logging
import platform
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
import pandas as pd

from config import ARTIFACTS_DIR, SEED, TARGET_COL, MLFLOW_EXP_FINAL

logger = logging.getLogger(__name__)


def export_test_predictions(
    calibrated_pipeline: Any,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    threshold: float,
) -> Path:
    """
    Produce test_predictions.parquet for Member 2.

    Columns:
        All original feature columns from X_test
        is_fraud (true label)
        calibrated_score (predicted probability)
        prediction (0 or 1, using threshold)

    Member 2 uses this to compute PCA / t-SNE / UMAP coordinates and
    to build the scatter plot data for the analyst dashboard.

    Args:
        calibrated_pipeline: Finalist calibrated Pipeline.
        X_test: fraudTest features.
        y_test: fraudTest labels.
        threshold: Decision threshold from part C.

    Returns:
        Path to the written parquet file.
    """
    raise NotImplementedError


def export_calibration_scores(
    calibrated_pipeline: Any,
    X_B: pd.DataFrame,
    y_B: pd.Series,
    X_C: pd.DataFrame,
    y_C: pd.Series,
    threshold: float,
) -> tuple[Path, Path]:
    """
    Export calibrated scores on parts B and C for Member 2.

    Part B scores: for conformal prediction calibration (Member 2).
    Part C scores: for threshold studies (Members 2 and 3).

    Returns:
        Tuple (path_to_scores_b, path_to_scores_c).
    """
    raise NotImplementedError


def build_model_card(
    selected_model: str,
    selection_report: Dict,
    metrics: Dict,
    inference_timing: Dict,
    calibration_info: Dict,
    threshold: float,
    mlflow_run_id: str,
    mlflow_model_version: str,
) -> Dict:
    """
    Build the model_card.json as specified in §15 of design v5.

    Contents:
        Model name and version (Contract 10)
        MLflow run ID and model version
        Training date
        Library versions (from importlib.metadata)
        Dataset name and date range (from DATASET.md — filled manually)
        Split description (A/B/C/test)
        Feature list with types and order
        Imbalance method
        Hyperparameters (best from Optuna)
        Selection rule and result (from selection_report)
        Calibration method and slice (B)
        Threshold and how it was chosen
        Cost matrix (assumptions)
        Headline metrics with CIs
        Inference time with hardware
        Reference statistics (training feature distributions — for Member 5 drift monitoring)
        Known limitations

    Args:
        selected_model: Name of the selected model.
        selection_report: Output of select_model.select_model().
        metrics: Output of evaluate.compute_all_metrics().
        inference_timing: Output of evaluate.measure_inference_time().
        calibration_info: Dict with method, brier_before, brier_after.
        threshold: Decision threshold from part C.
        mlflow_run_id: MLflow run ID of the final run.
        mlflow_model_version: Registered model version string.

    Returns:
        The model card dict (also written to ARTIFACTS_DIR / "model_card.json").
    """
    raise NotImplementedError


def export_all_artifacts(
    calibrated_pipeline: Any,
    base_pipeline: Any,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    X_B: pd.DataFrame,
    y_B: pd.Series,
    X_C: pd.DataFrame,
    y_C: pd.Series,
    threshold: float,
    selection_report: Dict,
    metrics: Dict,
    inference_timing: Dict,
    calibration_info: Dict,
    mlflow_run_id: str,
    mlflow_model_version: str,
) -> Dict[str, Path]:
    """
    Run all export functions in sequence and return paths to all artifacts.

    Args:
        calibrated_pipeline: Finalist calibrated Pipeline.
        base_pipeline: Finalist base (uncalibrated) Pipeline (for SHAP).
        X_test, y_test: fraudTest.
        X_B, y_B: Part B.
        X_C, y_C: Part C.
        threshold: Decision threshold from part C.
        selection_report: From select_model.select_model().
        metrics: From evaluate.compute_all_metrics().
        inference_timing: From evaluate.measure_inference_time().
        calibration_info: Dict with calibration summary.
        mlflow_run_id: MLflow final run ID.
        mlflow_model_version: Version string.

    Returns:
        Dict mapping artifact name → Path.
    """
    raise NotImplementedError
