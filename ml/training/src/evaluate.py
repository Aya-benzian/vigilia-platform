"""
evaluate.py — One-time final evaluation on fraudTest for Member 1 (§8 of design v5).

=============================================================================
IMPORTANT: This module is run ONCE at the very end, after model selection
and calibration are complete. fraudTest is NEVER used for any tuning,
selection, or calibration decision.
=============================================================================

Produces per-model evaluation report:
    - Accuracy (reported but explained as insufficient for imbalanced data)
    - Precision, Recall, F1 at the stated threshold (from part C)
    - ROC-AUC
    - PR-AUC (headline metric)
    - MCC (Matthews Correlation Coefficient)
    - Brier score
    - Reliability / calibration curves
    - Inference time (single-row and batch)

Also produces:
    - Cost view (§8): expected cost with cost matrix from config.py
    - Review-budget curve (§8): recall if analysts can review top-k% highest-scored
    - [NICE] Abstention curve: performance on kept cases when x% near threshold are abstained

All outputs are logged to MLflow under MLFLOW_EXP_FINAL.
"""

import logging
import time
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    brier_score_loss,
    f1_score,
    matthews_corrcoef,
    precision_score,
    recall_score,
    roc_auc_score,
)

from config import FIGURES_DIR, TABLES_DIR, TARGET_COL, MLFLOW_EXP_FINAL

logger = logging.getLogger(__name__)


def compute_all_metrics(
    y_true: pd.Series,
    y_prob: np.ndarray,
    threshold: float,
    model_name: str,
) -> Dict[str, float]:
    """
    Compute the full metric set for one model on fraudTest.

    Args:
        y_true: True labels from fraudTest.
        y_prob: Calibrated predicted probabilities (positive class).
        threshold: Decision threshold chosen on part C (from calibrate.py).
        model_name: For logging.

    Returns:
        Dict with keys: accuracy, precision, recall, f1, roc_auc, pr_auc,
                        mcc, brier, threshold.
    """
    raise NotImplementedError


def measure_inference_time(
    calibrated_pipeline: Any,
    X_sample: pd.DataFrame,
    n_single: int = 1000,
) -> Dict[str, float]:
    """
    Measure inference latency for the full Pipeline (preprocessing + model).

    Reports:
        single_row_median_ms : median latency for 1-row prediction (n_single calls)
        single_row_p95_ms    : 95th percentile latency for 1-row prediction
        batch_throughput_rps : rows per second for batch prediction on X_sample

    Hardware must be reported in model_card.json alongside these numbers.

    Args:
        calibrated_pipeline: Full calibrated Pipeline.
        X_sample: Sample DataFrame for batch timing (use part of fraudTest).
        n_single: Number of single-row calls for median/p95 estimation.

    Returns:
        Dict with timing metrics.
    """
    raise NotImplementedError


def evaluate_all_models(
    calibrated_pipelines: Dict[str, Any],
    thresholds: Dict[str, float],
    X_test: pd.DataFrame,
    y_test: pd.Series,
) -> pd.DataFrame:
    """
    Evaluate all five calibrated models on fraudTest.

    Args:
        calibrated_pipelines: Dict model_name → calibrated Pipeline.
        thresholds: Dict model_name → threshold chosen on part C.
        X_test, y_test: fraudTest features and labels.

    Returns:
        DataFrame with one row per model and all metrics as columns.
        Written to TABLES_DIR / "test_metrics.csv".
    """
    raise NotImplementedError
