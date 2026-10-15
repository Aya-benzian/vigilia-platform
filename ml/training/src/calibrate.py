"""
calibrate.py — Model calibration for Member 1 (§10 of design v5).

Calibrates ALL five models (not just the winner) using part B of fraudTrain.
Compares sigmoid vs. isotonic calibration using Brier score and reliability
curves on fraudTest (never on part B).

Why calibration matters:
    Class-weight adjustments distort raw probabilities. A calibrated model's
    probability output should match actual fraud frequencies (e.g., if the model
    outputs 0.8, about 80% of such cases should truly be fraud).
    This is essential because the system uses probabilities to decide whether
    to route to human review.

Key implementation detail (design v5 §3):
    Use FrozenEstimator to prevent the base model from being retrained on part B.
    The base model is fitted on part A ONLY and is never touched again.

    from sklearn.frozen import FrozenEstimator
    from sklearn.calibration import CalibratedClassifierCV
    cal = CalibratedClassifierCV(FrozenEstimator(fitted_model), method="isotonic")
    cal.fit(X_B, y_B)

    For SVM (no predict_proba): wrap the LinearSVC/SVC the same way — the
    calibrator will use decision_function internally.

Handoff to Member 2 (Contract 3):
    - The calibrated pipeline is what Member 2 serves.
    - The BASE (uncalibrated) model is exported separately so Member 2 can
      run SHAP on it (explanations use the base model, not the calibrator).
    - Member 2 is told that calibration slice = part B.
"""

import logging
from pathlib import Path
from typing import Dict, Literal, Tuple, Any

import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV, calibration_curve
from sklearn.frozen import FrozenEstimator
from sklearn.metrics import brier_score_loss

from config import SEED, ARTIFACTS_DIR, FIGURES_DIR, TABLES_DIR, TARGET_COL

logger = logging.getLogger(__name__)

CalibrationMethod = Literal["isotonic", "sigmoid"]


def calibrate_model(
    fitted_pipeline: Any,
    X_B: pd.DataFrame,
    y_B: pd.Series,
    method: CalibrationMethod = "isotonic",
) -> CalibratedClassifierCV:
    """
    Fit a calibrator on part B without retraining the base model.

    Uses FrozenEstimator to guarantee the base model is unchanged.
    For SVM (no predict_proba), the calibrator uses decision_function.

    Args:
        fitted_pipeline: A Pipeline already fitted on part A.
        X_B: Features from part B (calibration set).
        y_B: Target from part B.
        method: "isotonic" or "sigmoid". Compare both — see compare_calibration_methods().

    Returns:
        Fitted CalibratedClassifierCV wrapping the frozen base pipeline.
    """
    raise NotImplementedError


def compare_calibration_methods(
    fitted_pipeline: Any,
    X_B: pd.DataFrame,
    y_B: pd.Series,
    X_test: pd.DataFrame,
    y_test: pd.Series,
) -> pd.DataFrame:
    """
    Compare isotonic vs. sigmoid calibration using Brier score on fraudTest.

    Design v5 §10: "With very few frauds, sigmoid is safer."
    This function runs both and returns the comparison so the decision is
    empirically justified (not assumed).

    NEVER uses part B to evaluate — always evaluates on fraudTest.

    Args:
        fitted_pipeline: Base pipeline fitted on part A.
        X_B, y_B: Calibration data (part B).
        X_test, y_test: Final test data (fraudTest). Used for evaluation only.

    Returns:
        DataFrame with columns: method, brier_before, brier_after.
    """
    raise NotImplementedError


def brier_score(y_true: pd.Series, y_prob: np.ndarray) -> float:
    """
    Compute Brier score (lower is better, 0 = perfect).

    Args:
        y_true: True binary labels.
        y_prob: Predicted probabilities for the positive class.

    Returns:
        Brier score.
    """
    raise NotImplementedError


def plot_reliability_curve(
    y_true: pd.Series,
    y_prob_before: np.ndarray,
    y_prob_after: np.ndarray,
    model_name: str,
) -> Path:
    """
    Plot reliability curves (calibration curves) before and after calibration.

    Uses quantile-based binning because fraud is rare (frequency-based bins
    would be nearly empty for fraud cases).

    This is Figure 2 of the 3-figure demo (§17 of design v5).
    Saved to FIGURES_DIR / f"reliability_{model_name}.png".

    Args:
        y_true: True binary labels (from fraudTest).
        y_prob_before: Uncalibrated probabilities.
        y_prob_after: Calibrated probabilities.
        model_name: Used in filename and plot title.

    Returns:
        Path to the saved figure.
    """
    raise NotImplementedError


def calibrate_all_models(
    fitted_pipelines: Dict[str, Any],
    X_B: pd.DataFrame,
    y_B: pd.Series,
    X_test: pd.DataFrame,
    y_test: pd.Series,
) -> Tuple[Dict[str, Any], pd.DataFrame]:
    """
    Calibrate all five models and return calibrated pipelines + Brier comparison.

    Steps per model:
        1. Choose calibration method (compare isotonic vs. sigmoid).
        2. Fit chosen calibrator on part B.
        3. Evaluate Brier before/after on fraudTest.
        4. Plot reliability curve.
        5. Check that PR-AUC does not drop noticeably after calibration.
        6. Save calibrated pipeline to ARTIFACTS_DIR.

    Args:
        fitted_pipelines: Dict model_name → pipeline fitted on part A.
        X_B, y_B: Calibration data (part B).
        X_test, y_test: Final test set. Only used for evaluation metrics here.

    Returns:
        Tuple:
            calibrated_pipelines : Dict model_name → calibrated pipeline.
            brier_df             : DataFrame with before/after Brier scores per model.
    """
    raise NotImplementedError


def choose_threshold_on_part_c(
    calibrated_pipeline: Any,
    X_C: pd.DataFrame,
    y_C: pd.Series,
    strategy: str = "maximize_f1",
) -> float:
    """
    Choose the decision threshold on part C using calibrated probabilities.

    Strategies:
        "maximize_f1"   : threshold that maximizes F1 on part C (default).
        "fix_precision" : threshold that achieves a target precision (set in config.py).

    The threshold is saved in model_card.json and NEVER re-tuned on fraudTest.
    The final business threshold is Member 3's responsibility — this threshold
    is only used for reporting precision, recall, F1, and MCC.

    Args:
        calibrated_pipeline: Calibrated pipeline for the finalist model.
        X_C, y_C: Part C features and labels.
        strategy: Threshold selection strategy.

    Returns:
        Chosen threshold (float between 0 and 1).
    """
    raise NotImplementedError
