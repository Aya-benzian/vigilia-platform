"""
cv_compare.py — Repeated cross-validation model comparison (Member 1, §7).

Two CV strategies on the comparison subsample of part A:
1. RepeatedStratifiedKFold (5x10) — for i.i.d. baseline
2. StratifiedGroupKFold (5 folds, groups=cc_num) — simulates cardholder leakage

All models are evaluated on IDENTICAL folds so pairwise statistical tests
in stats_tests.py are valid.

Results are a Dict[model_name -> scores_array] logged to MLflow under
MLFLOW_EXP_COMPARE.
"""

import logging
from typing import Dict

import mlflow
import numpy as np
import pandas as pd
from sklearn.model_selection import (
    RepeatedStratifiedKFold,
    StratifiedGroupKFold,
    cross_val_score,
)

from config import (
    SEED,
    CV_N_SPLITS,
    CV_N_REPEATS,
    MLFLOW_EXP_COMPARE,
    GROUP_COL,
    TARGET_COL,
)
from models import make_logreg, make_random_forest, make_xgboost, make_svm, make_mlp
from baselines import make_dummy_classifier
from preprocess import build_pipeline

logger = logging.getLogger(__name__)
SCORING = "average_precision"


# ==============================================================================
# CV strategies
# ==============================================================================

def make_repeated_stratified_cv() -> RepeatedStratifiedKFold:
    """5-fold × 10-repeat = 50 scores per model."""
    return RepeatedStratifiedKFold(
        n_splits=CV_N_SPLITS,
        n_repeats=CV_N_REPEATS,
        random_state=SEED,
    )


def make_group_cv() -> StratifiedGroupKFold:
    """5-fold stratified group CV (group = cc_num)."""
    return StratifiedGroupKFold(n_splits=CV_N_SPLITS)  # NOSONAR — no random_state: StratifiedGroupKFold is deterministic


# ==============================================================================
# Compare all models
# ==============================================================================

def compare_models(
    X_compare: pd.DataFrame,
    y_compare: pd.Series,
    best_params: Dict[str, dict],
) -> Dict[str, np.ndarray]:
    """
    Evaluate all 7 models (5 + 2 baselines) on identical repeated CV folds.

    Args:
        X_compare: Comparison subsample feature DataFrame (disjoint from tune set).
        y_compare: Target Series.
        best_params: Dict model_name -> Optuna best params
                     (from tune.run_all_tuning).

    Returns:
        Dict mapping model_name -> np.ndarray of PR-AUC scores
        (length = CV_N_SPLITS * CV_N_REPEATS for repeated, CV_N_SPLITS for group).
    """
    mlflow.set_experiment(MLFLOW_EXP_COMPARE)

    # Compute scale_pos_weight for XGBoost
    n_neg = (y_compare == 0).sum()
    n_pos = (y_compare == 1).sum()
    spw   = float(n_neg / max(n_pos, 1))

    # Build all pipelines with best params
    pipelines = {
        "dummy":   build_pipeline(make_dummy_classifier("stratified"), "dummy"),
        "logreg":  build_pipeline(make_logreg(best_params.get("logreg")),   "logreg"),
        "rf":      build_pipeline(make_random_forest(best_params.get("rf")), "rf"),
        "xgboost": build_pipeline(make_xgboost(best_params.get("xgboost"), spw), "xgboost"),
        "svm":     build_pipeline(make_svm(best_params.get("svm")),          "svm"),
        "mlp":     build_pipeline(make_mlp(best_params.get("mlp")),          "mlp"),
    }

    cv_repeated = make_repeated_stratified_cv()
    results: Dict[str, np.ndarray] = {}

    # --- Repeated Stratified CV ---
    with mlflow.start_run(run_name="cv_compare_repeated"):
        mlflow.log_params({
            "cv_strategy":  "RepeatedStratifiedKFold",
            "n_splits":     CV_N_SPLITS,
            "n_repeats":    CV_N_REPEATS,
            "n_rows":       len(X_compare),
            "fraud_rate":   float(y_compare.mean()),
            "scoring":      SCORING,
        })

        for name, pipe in pipelines.items():
            logger.info(f"  CV comparing: {name}")
            scores = cross_val_score(
                pipe, X_compare, y_compare,
                cv=cv_repeated,
                scoring=SCORING,
                n_jobs=-1,
                error_score=0.0,
            )
            results[name] = scores
            mlflow.log_metric(f"{name}_pr_auc_mean", float(scores.mean()))
            mlflow.log_metric(f"{name}_pr_auc_std",  float(scores.std()))
            logger.info(
                f"    {name}: PR-AUC = {scores.mean():.4f} ± {scores.std():.4f}"
            )

    logger.info("Repeated CV complete.")
    return results


def compare_models_group_cv(
    X_compare: pd.DataFrame,
    y_compare: pd.Series,
    groups: pd.Series,
    best_params: Dict[str, dict],
) -> Dict[str, np.ndarray]:
    """
    Same as compare_models() but uses StratifiedGroupKFold (groups = cc_num).

    Folds are created so no cardholder appears in both train and test fold.
    This simulates the scenario where the ML system sees entirely new cardholders.

    Args:
        X_compare: Comparison subsample feature DataFrame.
        y_compare: Target Series.
        groups: cc_num Series (same index as X_compare).
        best_params: Dict model_name -> Optuna best params.

    Returns:
        Dict mapping model_name -> np.ndarray of PR-AUC scores (len = CV_N_SPLITS).
    """
    mlflow.set_experiment(MLFLOW_EXP_COMPARE)

    n_neg = (y_compare == 0).sum()
    n_pos = (y_compare == 1).sum()
    spw   = float(n_neg / max(n_pos, 1))

    pipelines = {
        "dummy":   build_pipeline(make_dummy_classifier("stratified"), "dummy"),
        "logreg":  build_pipeline(make_logreg(best_params.get("logreg")),   "logreg"),
        "rf":      build_pipeline(make_random_forest(best_params.get("rf")), "rf"),
        "xgboost": build_pipeline(make_xgboost(best_params.get("xgboost"), spw), "xgboost"),
        "svm":     build_pipeline(make_svm(best_params.get("svm")),          "svm"),
        "mlp":     build_pipeline(make_mlp(best_params.get("mlp")),          "mlp"),
    }

    cv_group = make_group_cv()
    results: Dict[str, np.ndarray] = {}

    with mlflow.start_run(run_name="cv_compare_group"):
        mlflow.log_params({
            "cv_strategy":  "StratifiedGroupKFold",
            "n_splits":     CV_N_SPLITS,
            "n_unique_groups": int(groups.nunique()),
            "n_rows":       len(X_compare),
            "fraud_rate":   float(y_compare.mean()),
            "scoring":      SCORING,
        })

        for name, pipe in pipelines.items():
            logger.info(f"  Group CV comparing: {name}")
            scores = cross_val_score(
                pipe, X_compare, y_compare,
                cv=cv_group,
                groups=groups,
                scoring=SCORING,
                n_jobs=-1,
                error_score=0.0,
            )
            results[name] = scores
            mlflow.log_metric(f"{name}_group_pr_auc_mean", float(scores.mean()))
            mlflow.log_metric(f"{name}_group_pr_auc_std",  float(scores.std()))
            logger.info(
                f"    {name} (group): PR-AUC = {scores.mean():.4f} ± {scores.std():.4f}"
            )

    logger.info("Group CV complete.")
    return results
