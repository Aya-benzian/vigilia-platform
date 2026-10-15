"""
calibrate.py — Probability calibration and threshold selection (Member 1, §10-11).

Two steps:
1. Calibrate the champion pipeline on part B using CalibratedClassifierCV
   with FrozenEstimator (sklearn 1.6+) so the pipeline weights are not retrained.
2. Choose the operating threshold on part C by maximizing F2 score
   (weighs recall 2x over precision — catching fraud matters more than false positives).

Outputs:
    - calibrated_pipeline: the calibrated pipeline object (logged to MLflow)
    - threshold_: the operating threshold (logged to MLflow)
    - Reliability curve figure (reports/figures/reliability_curve.png)
"""

import logging
from pathlib import Path
from typing import Optional

import mlflow
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from sklearn.calibration import CalibratedClassifierCV, CalibrationDisplay
from sklearn.frozen import FrozenEstimator
from sklearn.metrics import (
    brier_score_loss,
    f1_score,
    fbeta_score,
    precision_recall_curve,
)
from sklearn.pipeline import Pipeline

from config import SEED, ARTIFACTS_DIR, REPORTS_DIR

logger = logging.getLogger(__name__)

FIGURES_DIR = REPORTS_DIR / "figures"
FIGURES_DIR.mkdir(parents=True, exist_ok=True)


# ==============================================================================
# Calibration
# ==============================================================================

def calibrate_pipeline(
    fitted_pipeline: Pipeline,
    X_b: pd.DataFrame,
    y_b: pd.Series,
    method: str = "isotonic",
    cv: str = "prefit",
) -> Pipeline:
    """
    Wrap the champion pipeline with CalibratedClassifierCV on part B.

    Uses FrozenEstimator (sklearn 1.6+) so that the pipeline weights are
    NOT retrained — only the calibrator layer is fitted on part B.

    Args:
        fitted_pipeline: Champion pipeline already fitted on part A.
        X_b: Part B features (never seen by the pipeline during training).
        y_b: Part B target.
        method: "isotonic" (default) or "sigmoid" (Platt scaling).
        cv: "prefit" — the pipeline is already fitted, only the calibrator is fit.

    Returns:
        A new sklearn Pipeline with calibration applied.
    """
    logger.info(f"Calibrating with method='{method}' on part B ({len(X_b):,} rows)...")

    # FrozenEstimator prevents refitting the inner pipeline
    frozen = FrozenEstimator(fitted_pipeline)

    calibrator = CalibratedClassifierCV(
        estimator=frozen,
        method=method,
        cv=cv,
    )
    calibrator.fit(X_b, y_b)

    # Brier score on B (lower = better)
    probs = calibrator.predict_proba(X_b)[:, 1]
    brier = brier_score_loss(y_b, probs)
    logger.info(f"Calibration Brier score on part B: {brier:.4f}")

    return calibrator


# ==============================================================================
# Reliability curve figure
# ==============================================================================

def plot_reliability_curve(
    y_true: np.ndarray,
    y_prob_uncal: np.ndarray,
    y_prob_cal: np.ndarray,
    filename: str = "reliability_curve.png",
) -> Path:
    """
    Plot reliability (calibration) curves before and after calibration.

    Args:
        y_true: True binary labels.
        y_prob_uncal: Uncalibrated predicted probabilities.
        y_prob_cal: Calibrated predicted probabilities.
        filename: Output filename.

    Returns:
        Path to saved figure.
    """
    fig, ax = plt.subplots(figsize=(7, 6))

    CalibrationDisplay.from_predictions(
        y_true, y_prob_uncal,
        n_bins=10, ax=ax, name="Before calibration",
        color="#e74c3c",
    )
    CalibrationDisplay.from_predictions(
        y_true, y_prob_cal,
        n_bins=10, ax=ax, name="After calibration",
        color="#2ecc71",
    )

    ax.plot([0, 1], [0, 1], "k--", label="Perfect calibration")
    ax.set_title("Reliability Curve (part B)", fontsize=13, fontweight="bold")
    ax.legend(loc="lower right")
    plt.tight_layout()

    out = FIGURES_DIR / filename
    fig.savefig(out, dpi=150)
    plt.close(fig)
    logger.info(f"Reliability curve saved to {out}")
    return out


# ==============================================================================
# Threshold selection on part C
# ==============================================================================

def select_threshold(
    calibrated_pipeline,
    X_c: pd.DataFrame,
    y_c: pd.Series,
    beta: float = 2.0,
) -> float:
    """
    Select the operating threshold on part C by maximising F-beta score.

    Default beta=2.0: recall is weighted 2× more than precision.
    This reflects the business cost of a missed fraud (FN) being higher
    than a false alarm (FP → human review).

    Args:
        calibrated_pipeline: Calibrated pipeline fitted on part B.
        X_c: Part C features.
        y_c: Part C target.
        beta: F-beta parameter. Default 2.0.

    Returns:
        Optimal threshold float in [0, 1].
    """
    logger.info(f"Selecting threshold on part C ({len(X_c):,} rows, beta={beta})...")

    probs = calibrated_pipeline.predict_proba(X_c)[:, 1]
    precisions, recalls, thresholds = precision_recall_curve(y_c, probs)

    # precision_recall_curve returns one fewer threshold than metric values
    # last element is always precision=1.0, recall=0.0 at threshold=max
    fbeta_scores = (
        (1 + beta ** 2)
        * precisions[:-1]
        * recalls[:-1]
        / (beta ** 2 * precisions[:-1] + recalls[:-1] + 1e-9)
    )

    best_idx       = int(np.argmax(fbeta_scores))
    best_threshold = float(thresholds[best_idx])
    best_fbeta     = float(fbeta_scores[best_idx])

    # Compute F1 and F2 at chosen threshold for logging
    preds = (probs >= best_threshold).astype(int)
    f1    = f1_score(y_c, preds, zero_division=0)
    f2    = fbeta_score(y_c, preds, beta=beta, zero_division=0)

    logger.info(
        f"Threshold selected: {best_threshold:.4f} | "
        f"F{beta:.0f}={best_fbeta:.4f} | F1={f1:.4f} | F2={f2:.4f}"
    )
    return best_threshold


# ==============================================================================
# PR curve figure
# ==============================================================================

def plot_pr_curve(
    calibrated_pipeline,
    X_c: pd.DataFrame,
    y_c: pd.Series,
    threshold: float,
    filename: str = "pr_curve_part_c.png",
) -> Path:
    """
    Precision-Recall curve on part C with threshold marked.

    Args:
        calibrated_pipeline: Calibrated pipeline.
        X_c, y_c: Part C data.
        threshold: Operating threshold chosen by select_threshold.
        filename: Output filename.

    Returns:
        Path to saved figure.
    """
    probs = calibrated_pipeline.predict_proba(X_c)[:, 1]
    precisions, recalls, thresholds = precision_recall_curve(y_c, probs)

    # Find point on curve closest to chosen threshold
    idx    = np.searchsorted(thresholds, threshold, side="right")
    idx    = min(idx, len(recalls) - 2)
    op_rec = recalls[idx]
    op_pre = precisions[idx]

    fig, ax = plt.subplots(figsize=(7, 6))
    ax.plot(recalls, precisions, color="#3498db", linewidth=2, label="PR curve (part C)")
    ax.scatter([op_rec], [op_pre], color="#e74c3c", s=120, zorder=5,
               label=f"Operating point (thr={threshold:.3f})")
    ax.set_xlabel("Recall")
    ax.set_ylabel("Precision")
    ax.set_title("Precision-Recall Curve — Part C", fontsize=13, fontweight="bold")
    ax.legend()
    ax.grid(linestyle="--", alpha=0.5)
    plt.tight_layout()

    out = FIGURES_DIR / filename
    fig.savefig(out, dpi=150)
    plt.close(fig)
    logger.info(f"PR curve saved to {out}")
    return out


# ==============================================================================
# Full calibration pipeline
# ==============================================================================

def run_calibration(
    fitted_pipeline: Pipeline,
    X_b: pd.DataFrame,
    y_b: pd.Series,
    X_c: pd.DataFrame,
    y_c: pd.Series,
    method: str = "isotonic",
    beta: float = 2.0,
) -> tuple:
    """
    Full calibration and threshold selection flow.

    Steps:
        1. Calibrate fitted_pipeline on part B.
        2. Plot reliability curve.
        3. Select threshold on part C.
        4. Plot PR curve.
        5. Log everything to MLflow.

    Args:
        fitted_pipeline: Champion pipeline fitted on part A.
        X_b, y_b: Part B (calibration set).
        X_c, y_c: Part C (threshold selection set).
        method: Calibration method ("isotonic" or "sigmoid").
        beta: F-beta for threshold selection.

    Returns:
        Tuple (calibrated_pipeline, threshold).
    """
    mlflow.set_experiment("fraud-sparkov-compare")

    with mlflow.start_run(run_name="calibration"):

        # 1. Calibrate
        cal_pipe = calibrate_pipeline(fitted_pipeline, X_b, y_b, method=method)
        mlflow.log_param("calibration_method", method)
        mlflow.log_param("calibration_set_size", len(X_b))
        mlflow.log_metric("brier_part_b",
            brier_score_loss(y_b, cal_pipe.predict_proba(X_b)[:, 1]))

        # 2. Reliability curve
        uncal_probs = fitted_pipeline.predict_proba(X_b)[:, 1]
        cal_probs   = cal_pipe.predict_proba(X_b)[:, 1]
        rel_path    = plot_reliability_curve(y_b.values, uncal_probs, cal_probs)
        mlflow.log_artifact(str(rel_path))

        # 3. Threshold selection
        threshold   = select_threshold(cal_pipe, X_c, y_c, beta=beta)
        mlflow.log_metric("operating_threshold", threshold)
        mlflow.log_param("threshold_beta", beta)

        # 4. PR curve
        pr_path = plot_pr_curve(cal_pipe, X_c, y_c, threshold)
        mlflow.log_artifact(str(pr_path))

        logger.info(f"Calibration complete. Threshold={threshold:.4f}")

    return cal_pipe, threshold
