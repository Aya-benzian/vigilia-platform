"""
cv_compare.py — Repeated cross-validation model comparison for Member 1 (§9 of design v5).

Two CV protocols are run on IDENTICAL folds:
1. RepeatedStratifiedKFold       — headline result (matches deployment where customers are known)
2. StratifiedGroupKFold (repeat) — sensitivity analysis grouped by cc_num
   to detect customer fingerprint overfitting

All models are evaluated on the SAME folds so scores are paired for Friedman/Wilcoxon.

Output: tables/cv_scores_long.parquet
    Columns: model, fold, repeat, metric, value
    Loaded by stats_tests.py for all statistical testing.

Rules:
- Frozen best hyperparameters from tune.py must be set before calling this module.
- Resampling (if any) only inside training folds, never on validation folds.
- All preprocessing fitted inside the Pipeline inside each fold.
- Seed = SEED everywhere.
- Metrics per fold: PR-AUC (primary), F1, MCC (secondary).
- Log fold scores to MLflow under MLFLOW_EXP_COMPARE.
"""

import logging
from typing import Dict, Any, List

import numpy as np
import pandas as pd
from sklearn.model_selection import RepeatedStratifiedKFold, StratifiedGroupKFold

from config import (
    SEED,
    CV_N_SPLITS,
    CV_N_REPEATS,
    CV_PRIMARY_METRIC,
    CV_SECONDARY_METRICS,
    TABLES_DIR,
    GROUP_COL,
    TARGET_COL,
    MLFLOW_EXP_COMPARE,
)

logger = logging.getLogger(__name__)


def run_repeated_stratified_cv(
    pipelines: Dict[str, Any],
    X: pd.DataFrame,
    y: pd.Series,
) -> pd.DataFrame:
    """
    Run RepeatedStratifiedKFold CV for all models on IDENTICAL folds.

    n_splits=CV_N_SPLITS (5), n_repeats=CV_N_REPEATS (10) → 50 scores per model.

    Args:
        pipelines: Dict mapping model name → unfitted sklearn Pipeline.
                   Pipelines are cloned internally so originals are unchanged.
        X: Comparison subsample feature DataFrame (from part A).
        y: Target Series.

    Returns:
        Long-format DataFrame with columns:
            model, fold, repeat, pr_auc, f1, mcc
        Also written to TABLES_DIR / "cv_scores_stratified.parquet".
    """
    raise NotImplementedError


def run_grouped_cv_sensitivity(
    pipelines: Dict[str, Any],
    X: pd.DataFrame,
    y: pd.Series,
    groups: pd.Series,
    n_repeats: int = CV_N_REPEATS,
) -> pd.DataFrame:
    """
    Run StratifiedGroupKFold CV grouped by cc_num (customer fingerprint sensitivity).

    Each repeat uses a different random permutation of the data to approximate
    repeated grouped CV (StratifiedGroupKFold has no native n_repeats).

    Args:
        pipelines: Dict mapping model name → unfitted sklearn Pipeline.
        X: Comparison subsample feature DataFrame.
        y: Target Series.
        groups: Series of cc_num values (group key — never a feature).
        n_repeats: Number of shuffle repeats.

    Returns:
        Long-format DataFrame same structure as run_repeated_stratified_cv().
        Also written to TABLES_DIR / "cv_scores_grouped.parquet".

    Notes:
        If the model ranking changes vs. the stratified CV, report this and follow
        the selection rule in select_model.py to handle the discrepancy.
    """
    raise NotImplementedError


def merge_cv_results(
    stratified_df: pd.DataFrame,
    grouped_df: pd.DataFrame,
) -> pd.DataFrame:
    """
    Merge both CV result DataFrames and add a 'cv_type' column.

    Output written to TABLES_DIR / "cv_scores_long.parquet" (loaded by stats_tests.py).

    Args:
        stratified_df: Output of run_repeated_stratified_cv().
        grouped_df: Output of run_grouped_cv_sensitivity().

    Returns:
        Combined long-format DataFrame with cv_type in {"stratified", "grouped"}.
    """
    raise NotImplementedError
