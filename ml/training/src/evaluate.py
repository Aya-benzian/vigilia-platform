"""
evaluate.py — Final test set evaluation (Member 1, §12).

WARNING: This module is run EXACTLY ONCE on fraudTest.csv.
         Looking at test results multiple times = implicit tuning = cheating.

Metrics computed:
    PR-AUC (primary), ROC-AUC, F1, F2, MCC, Brier score,
    inference time (ms/sample), confusion matrix.

All results saved to reports/tables/ and logged to MLflow.
"""

import logging
import time
from pathlib import Path
from typing import Optional

import mlflow
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    fbeta_score,
    matthews_corrcoef,
    roc_auc_score,
    ConfusionMatrixDisplay,
)

from config import REPORTS_DIR, COST_FN, COST_FP, COST_TN, COST_TP

logger = logging.getLogger(__name__)

TABLES_DIR  = REPORTS_DIR / "tables"
FIGURES_DIR = REPORTS_DIR / "figures"


def _ensure_dirs():
    TABLES_DIR.mkdir(parents=True, exist_ok=True)
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)


# ==============================================================================
# Metrics
# ==============================================================================

def compute_metrics(
    y_true: np.ndarray,
    y_prob: np.ndarray,
    threshold: float,
    beta: float = 2.0,
) -> dict:
    """
    Compute all metrics at a given threshold.

    Args:
        y_true: True binary labels.
        y_prob: Calibrated predicted probabilities (positive class).
        threshold: Operating threshold from calibrate.select_threshold.
        beta: F-beta parameter (default 2.0).

    Returns:
        Dict of metric name -> value.
    """
    y_pred = (y_prob >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred).ravel()

    # Business cost
    total_cost = (
        COST_FN * fn + COST_FP * fp +
        COST_TN * tn + COST_TP * tp
    )

    return dict(
        pr_auc        = float(average_precision_score(y_true, y_prob)),
        roc_auc       = float(roc_auc_score(y_true, y_prob)),
        f1            = float(f1_score(y_true, y_pred, zero_division=0)),
        f2            = float(fbeta_score(y_true, y_pred, beta=beta, zero_division=0)),
        mcc           = float(matthews_corrcoef(y_true, y_pred)),
        brier         = float(brier_score_loss(y_true, y_prob)),
        threshold     = float(threshold),
        tp=int(tp), fp=int(fp), tn=int(tn), fn=int(fn),
        precision     = float(tp / max(tp + fp, 1)),
        recall        = float(tp / max(tp + fn, 1)),
        specificity   = float(tn / max(tn + fp, 1)),
        business_cost = float(total_cost),
        fraud_rate    = float(y_true.mean()),
    )


# ==============================================================================
# Inference timing
# ==============================================================================

def measure_inference_time(
    pipeline,
    X_test: pd.DataFrame,
    n_warmup: int = 3,
    n_reps: int = 50,
) -> float:
    """
    Measure mean inference latency in ms per sample.

    Runs n_warmup warmup iterations then n_reps timed iterations
    on a single row to simulate single-transaction serving latency.

    Args:
        pipeline: Fitted (calibrated) pipeline.
        X_test: Test feature DataFrame (use one row for serving latency).
        n_warmup: Warmup iterations (not timed).
        n_reps: Timed iterations.

    Returns:
        Mean latency in ms per single sample.
    """
    single_row = X_test.iloc[[0]]

    # Warmup
    for _ in range(n_warmup):
        pipeline.predict_proba(single_row)

    # Timed
    times = []
    for _ in range(n_reps):
        t0 = time.perf_counter()
        pipeline.predict_proba(single_row)
        times.append(time.perf_counter() - t0)

    mean_ms = float(np.mean(times) * 1000)
    logger.info(f"Inference latency: {mean_ms:.2f} ms/sample (n={n_reps})")
    return mean_ms


# ==============================================================================
# Figures
# ==============================================================================

def plot_confusion_matrix(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    filename: str = "confusion_matrix_test.png",
) -> Path:
    """Save confusion matrix heatmap."""
    _ensure_dirs()
    fig, ax = plt.subplots(figsize=(5, 4))
    cm = confusion_matrix(y_true, y_pred)
    ConfusionMatrixDisplay(cm, display_labels=["Legit", "Fraud"]).plot(
        ax=ax, colorbar=False, cmap="Blues"
    )
    ax.set_title("Confusion Matrix — fraudTest", fontsize=13, fontweight="bold")
    plt.tight_layout()
    out = FIGURES_DIR / filename
    fig.savefig(out, dpi=150)
    plt.close(fig)
    logger.info(f"Confusion matrix saved to {out}")
    return out


# ==============================================================================
# Main evaluation entry point
# ==============================================================================

def evaluate_on_test(
    calibrated_pipeline,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    threshold: float,
    model_name: str = "champion",
) -> dict:
    """
    Run final evaluation on fraudTest. Called ONCE at the very end.

    Logs all metrics, inference time, confusion matrix, and business cost
    to MLflow under a dedicated run.

    Args:
        calibrated_pipeline: Calibrated champion pipeline.
        X_test: fraudTest feature DataFrame.
        y_test: fraudTest target Series.
        threshold: Operating threshold from calibrate.select_threshold.
        model_name: For MLflow run naming.

    Returns:
        Dict of all metrics.
    """
    _ensure_dirs()
    logger.info(
        f"=== FINAL TEST EVALUATION on fraudTest ({len(X_test):,} rows) ==="
    )
    logger.warning("This should be run EXACTLY ONCE. Do not re-run after seeing results.")

    # Predict
    y_prob = calibrated_pipeline.predict_proba(X_test)[:, 1]
    y_pred = (y_prob >= threshold).astype(int)

    # Metrics
    metrics = compute_metrics(y_test.values, y_prob, threshold)

    # Inference time
    latency_ms = measure_inference_time(calibrated_pipeline, X_test)
    metrics["inference_ms_per_sample"] = latency_ms

    # Confusion matrix figure
    cm_path = plot_confusion_matrix(y_test.values, y_pred)

    # Save metrics table
    metrics_df = pd.DataFrame([metrics])
    csv_path = TABLES_DIR / "final_test_metrics.csv"
    metrics_df.to_csv(csv_path, index=False)
    logger.info(f"Final metrics saved to {csv_path}")

    # Log to MLflow
    with mlflow.start_run(run_name=f"final_eval_{model_name}"):
        mlflow.log_params({"model": model_name, "threshold": threshold})
        for k, v in metrics.items():
            if isinstance(v, (int, float)):
                mlflow.log_metric(k, v)
        mlflow.log_artifact(str(cm_path))
        mlflow.log_artifact(str(csv_path))

    # Print summary
    logger.info(
        f"RESULTS: PR-AUC={metrics['pr_auc']:.4f} | "
        f"ROC-AUC={metrics['roc_auc']:.4f} | "
        f"F1={metrics['f1']:.4f} | F2={metrics['f2']:.4f} | "
        f"MCC={metrics['mcc']:.4f} | Brier={metrics['brier']:.4f} | "
        f"Latency={latency_ms:.2f}ms"
    )
    return metrics
