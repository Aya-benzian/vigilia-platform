"""
benchmark_ulb.py — [NICE] ULB dataset ranking robustness benchmark (Member 1).

Purpose:
    Check that the MODEL RANKING holds on ULB (a real, anonymized dataset)
    to partially validate that the Sparkov results are not purely synthetic artifacts.

Rules (§2 of design v5):
    - NEVER merge ULB with Sparkov.
    - NEVER use ULB for final model selection (Sparkov is the primary dataset).
    - NEVER claim real-bank generalization from ULB results.
    - Train a SEPARATE pipeline on ULB and compare model rankings only.
    - Report: does XGBoost beat RF on ULB the same way it does on Sparkov?

ULB features: V1..V28 (PCA-anonymized), Amount, Time.
No engineered features needed — V1..V28 are already transformed.

Output: MLFLOW_EXP_ULB experiment with rank comparison table.
"""

import logging
from typing import Dict, Any

import pandas as pd

from config import (
    ULB_FILE,
    SEED,
    CV_N_SPLITS,
    CV_N_REPEATS,
    MLFLOW_EXP_ULB,
    TABLES_DIR,
)

logger = logging.getLogger(__name__)


def load_and_prepare_ulb() -> tuple[pd.DataFrame, pd.Series]:
    """
    Load creditcard.csv and prepare features and target.

    Features: V1..V28, Amount (log-transformed), Time.
    Target: Class (0=legitimate, 1=fraud).

    Returns:
        Tuple (X, y).
    """
    raise NotImplementedError


def run_ulb_benchmark(best_params: Dict[str, Dict[str, Any]]) -> pd.DataFrame:
    """
    Train and compare all five models on ULB using the same CV protocol.

    Uses the SAME best hyperparameters from Sparkov tuning (not re-tuned on ULB).
    This tests generalization of the hyperparameters, not just ranking.

    Args:
        best_params: Dict model_name → best params from tune.run_all_tuning().

    Returns:
        DataFrame with model ranking on ULB (mean PR-AUC per model).
        Logged to MLFLOW_EXP_ULB as a run artifact.
    """
    raise NotImplementedError
