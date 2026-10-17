"""
cost_review.py — Cost view and review-budget curves for Member 1 (§8 of design v5).

Two required plots:
1. COST VIEW [MUST]:
   Expected cost using the cost matrix from config.py.
   Assumptions must be stated clearly in the report.
   cost_fn = missed fraud = transaction amount (dynamic per row)
   cost_fp = false alarm  = COST_FP (fixed review cost, see config.py)

2. REVIEW-BUDGET CURVE [MUST]:
   If analysts can review only the top-k% highest-scored transactions,
   what recall and fraud-amount-caught do we achieve?
   k values: 0.5%, 1%, 2%, 5% (and more if useful).
   This is Figure 3 of the 3-figure demo (§17 of design v5).

3. ABSTENTION CURVE [NICE]:
   Performance on the kept cases when the x% of cases closest to the threshold
   are sent to human review (abstention based on calibrated probability distance
   from the threshold).
   This gives Members 2 and 3 evidence for their uncertainty thresholds.
   Note: the uncertainty METHOD is Member 2's responsibility.

All outputs logged to MLflow as artifacts.
"""

import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
import pandas as pd

from config import COST_FP, FIGURES_DIR, TABLES_DIR, TARGET_COL

logger = logging.getLogger(__name__)


def compute_expected_cost(
    y_true: pd.Series,
    y_prob: np.ndarray,
    amounts: pd.Series,
    threshold: float,
) -> Dict[str, float]:
    """
    Compute expected cost at a given threshold using the cost matrix.

    Cost assumptions (stated as assumptions in the report):
        False Negative (missed fraud) : transaction amount (varies per row)
        False Positive (false alarm)  : COST_FP from config.py (fixed)
        True Negative (correct legit) : 0
        True Positive (correct fraud) : 0

    Args:
        y_true: True labels.
        y_prob: Calibrated probabilities (positive class).
        amounts: Transaction amounts (for fn cost calculation).
        threshold: Decision threshold.

    Returns:
        Dict with keys: total_cost, fn_cost, fp_cost, n_fn, n_fp.
    """
    raise NotImplementedError


def review_budget_curve(
    y_true: pd.Series,
    y_prob: np.ndarray,
    amounts: pd.Series,
    k_values: Optional[List[float]] = None,
) -> pd.DataFrame:
    """
    Compute recall and fraud-amount-caught for different analyst review budgets.

    At each k (fraction of all transactions), analysts review the top-k%
    highest-scored transactions. We compute:
        recall         : fraction of actual frauds caught
        amount_caught  : sum of fraud amounts caught / total fraud amount

    Args:
        y_true: True labels (fraudTest).
        y_prob: Calibrated probabilities (positive class).
        amounts: Transaction amounts.
        k_values: List of budget fractions, e.g. [0.005, 0.01, 0.02, 0.05].
                  Default: [0.005, 0.01, 0.02, 0.03, 0.05, 0.10].

    Returns:
        DataFrame with columns: k_pct, n_reviewed, recall, amount_caught_pct.
        Written to TABLES_DIR / "review_budget.csv".
    """
    raise NotImplementedError


def plot_review_budget_curve(budget_df: pd.DataFrame) -> Path:
    """
    Plot recall and amount-caught vs review budget (%).

    This is Figure 3 of the 3-figure demo (§17 of design v5).
    Saved to FIGURES_DIR / "review_budget_curve.png".

    Args:
        budget_df: Output of review_budget_curve().

    Returns:
        Path to the saved figure.
    """
    raise NotImplementedError


def abstention_curve(
    y_true: pd.Series,
    y_prob: np.ndarray,
    threshold: float,
    abstain_fractions: Optional[List[float]] = None,
) -> pd.DataFrame:
    """
    [NICE] Compute performance on kept cases when abstaining on uncertain predictions.

    At each abstention fraction x:
        - Remove the x% of cases whose score is closest to threshold.
        - Compute PR-AUC, F1, recall on the remaining cases.

    This shows how performance improves if uncertain cases are routed to humans.
    Evidence for Member 2 (uncertainty thresholds) and Member 3 (business rules).

    Args:
        y_true: True labels.
        y_prob: Calibrated probabilities.
        threshold: Decision threshold from part C.
        abstain_fractions: E.g. [0.05, 0.10, 0.15, 0.20, 0.25].

    Returns:
        DataFrame with columns: abstain_pct, n_kept, pr_auc, f1, recall.
    """
    raise NotImplementedError
