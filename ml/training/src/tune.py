"""
tune.py — Optuna hyperparameter tuning for Member 1 (§6 of design v5).

One Optuna study per model, all targeting the same objective: PR-AUC
(average_precision_score) on an inner validation split drawn from the
TUNING subsample of part A.

Rules (no leakage):
- The tuning subsample is DISJOINT from the comparison subsample (io.py).
- Hyperparameters are frozen BEFORE running cv_compare.py.
- The same number of trials (OPTUNA_N_TRIALS=30) is used for all models
  where feasible. SVM and MLP may use a time-budget instead — document this.
- Every Optuna trial is logged as a child run in MLflow under:
  experiment = MLFLOW_EXP_TUNING
- The best params dict is returned and also logged to MLflow.

After this module runs, the frozen best params are passed to models.py factory
functions in select_model.py and cv_compare.py.
"""

import logging
from typing import Any, Callable, Dict

import mlflow
import optuna

from config import (
    SEED,
    OPTUNA_N_TRIALS,
    OPTUNA_TIMEOUT,
    OPTUNA_METRIC,
    OPTUNA_DIRECTION,
    MLFLOW_EXP_TUNING,
    SVM_SUBSAMPLE_N,
)

logger = logging.getLogger(__name__)
optuna.logging.set_verbosity(optuna.logging.WARNING)


def tune_logreg(X_tune, y_tune) -> Dict[str, Any]:
    """
    Run an Optuna study to find the best Logistic Regression hyperparameters.

    Search space:
        C       : loguniform(1e-4, 1e2)
        penalty : categorical(["l1", "l2"])
        solver  : determined by penalty ("saga" for l1, "lbfgs" for l2)

    Objective: 3-fold CV PR-AUC on X_tune / y_tune.

    Args:
        X_tune: Feature DataFrame (tuning subsample of part A).
        y_tune: Target Series.

    Returns:
        Dict of best hyperparameters to pass to make_logreg(params=...).
    """
    raise NotImplementedError


def tune_rf(X_tune, y_tune) -> Dict[str, Any]:
    """
    Run an Optuna study to find the best Random Forest hyperparameters.

    Search space:
        n_estimators    : int [100, 500]
        max_depth       : int [5, 30] or None
        min_samples_leaf: int [1, 20]
        max_features    : categorical(["sqrt", "log2", 0.3])

    Args:
        X_tune: Feature DataFrame (tuning subsample of part A).
        y_tune: Target Series.

    Returns:
        Dict of best hyperparameters.
    """
    raise NotImplementedError


def tune_xgboost(X_tune, y_tune, scale_pos_weight: float = 1.0) -> Dict[str, Any]:
    """
    Run an Optuna study to find the best XGBoost hyperparameters.

    Search space:
        learning_rate   : loguniform(1e-3, 0.3)
        max_depth       : int [3, 10]
        n_estimators    : int [100, 500]
        subsample       : uniform(0.5, 1.0)
        colsample_bytree: uniform(0.5, 1.0)
        reg_lambda      : loguniform(1e-2, 10.0)

    scale_pos_weight is fixed (not tuned) — computed from part A class distribution.

    Args:
        X_tune: Feature DataFrame.
        y_tune: Target Series.
        scale_pos_weight: Negatives / positives ratio from part A.

    Returns:
        Dict of best hyperparameters.
    """
    raise NotImplementedError


def tune_svm(X_tune_small, y_tune_small) -> Dict[str, Any]:
    """
    Run an Optuna study for SVM hyperparameters on a SMALL subsample.

    SVM is limited to SVM_SUBSAMPLE_N rows (too slow beyond that).
    This function receives an already-subsampled X_tune_small / y_tune_small.

    Search space (RBF SVC):
        C    : loguniform(1e-2, 1e3)
        gamma: loguniform(1e-4, 1.0) or "scale"

    If LinearSVC is used instead, search space is just C.
    Document which variant is used.

    Args:
        X_tune_small: Small tuning subsample (≤ SVM_SUBSAMPLE_N rows).
        y_tune_small: Target Series.

    Returns:
        Dict of best hyperparameters.
    """
    raise NotImplementedError


def tune_mlp(X_tune, y_tune) -> Dict[str, Any]:
    """
    Run an Optuna study to find the best MLP hyperparameters.

    Search space:
        n_layers       : int [1, 3]
        n_units_per_layer: categorical([32, 64, 128, 256])
        alpha          : loguniform(1e-5, 1e-1)
        learning_rate_init: loguniform(1e-4, 1e-1)

    Args:
        X_tune: Feature DataFrame.
        y_tune: Target Series.

    Returns:
        Dict of best hyperparameters (including hidden_layer_sizes tuple).
    """
    raise NotImplementedError


def run_all_tuning(X_tune, y_tune, scale_pos_weight: float = 1.0) -> Dict[str, Dict]:
    """
    Run all five Optuna studies and return frozen hyperparameters for all models.

    Logs each study's best params and best value to MLflow under MLFLOW_EXP_TUNING.

    Args:
        X_tune: Tuning subsample feature DataFrame.
        y_tune: Tuning subsample target Series.
        scale_pos_weight: For XGBoost.

    Returns:
        Dict mapping model name → best params dict.
        Example: {"logreg": {...}, "rf": {...}, "xgboost": {...}, "svm": {...}, "mlp": {...}}
    """
    raise NotImplementedError
