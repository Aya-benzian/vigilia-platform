"""
baselines.py — Trivial baseline models for Member 1.

Two baselines:
1. DummyClassifier (prior) — always predicts from class distribution.
2. AmountThresholdClassifier — flags fraud if amt > threshold (one-rule).

Both must be beaten by real models to justify the ML system.
"""

import logging

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, ClassifierMixin
from sklearn.dummy import DummyClassifier
from sklearn.metrics import f1_score
from sklearn.utils.validation import check_is_fitted

from config import SEED

logger = logging.getLogger(__name__)


# ==============================================================================
# Baseline 1: DummyClassifier (sklearn built-in)
# ==============================================================================

def make_dummy_classifier(
    strategy: str = "stratified",
    random_state: int = SEED,
) -> DummyClassifier:
    """
    Return a DummyClassifier.

    Strategies:
        "stratified"   : samples from training class distribution (default)
        "most_frequent": always predicts majority class (all legitimate)
        "prior"        : predicts class probabilities equal to training freq
    """
    return DummyClassifier(strategy=strategy, random_state=random_state)


# ==============================================================================
# Baseline 2: One-rule amount threshold classifier
# ==============================================================================

class AmountThresholdClassifier(BaseEstimator, ClassifierMixin):
    """
    One-rule baseline: predict FRAUD if transaction amount > threshold.

    The threshold is chosen during fit() by maximizing F1 on the training data.
    Intentionally simple — proves that ML adds value beyond a single rule.

    Attributes:
        threshold_: Best amount threshold found during fit().
        classes_: [0, 1]
        uses_log_: True if amt_log was used instead of amt.
    """

    def __init__(self):
        pass

    def fit(self, X: pd.DataFrame, y: pd.Series) -> "AmountThresholdClassifier":
        """
        Find the amount threshold that maximizes F1 on (X, y).

        Tries the raw `amt` column first; falls back to `amt_log` if not present.
        Searches over candidate percentiles (1% to 99%) of the amount column.

        Args:
            X: Feature DataFrame. Must contain `amt` or `amt_log`.
            y: Binary target (0/1).

        Returns:
            self (fitted).
        """
        if "amt" in X.columns:
            amounts = X["amt"].values.astype(float)
            self.uses_log_ = False
            col_used = "amt"
        elif "amt_log" in X.columns:
            amounts = X["amt_log"].values.astype(float)
            self.uses_log_ = True
            col_used = "amt_log"
        else:
            raise ValueError("AmountThresholdClassifier requires 'amt' or 'amt_log' column.")

        self.classes_ = np.array([0, 1])

        # Search percentiles 1% to 99%
        best_f1 = -1.0
        best_threshold = np.median(amounts)

        percentiles = np.percentile(amounts, np.arange(1, 100))
        for threshold in percentiles:
            preds = (amounts > threshold).astype(int)
            score = f1_score(y, preds, zero_division=0)
            if score > best_f1:
                best_f1 = score
                best_threshold = threshold

        self.threshold_ = best_threshold
        logger.info(
            f"AmountThresholdClassifier: best threshold={self.threshold_:.4f} "
            f"(column={col_used}), F1={best_f1:.4f}"
        )
        return self

    def _get_amounts(self, X: pd.DataFrame) -> np.ndarray:
        """Extract the amount column used during fit."""
        check_is_fitted(self, ["threshold_", "uses_log_"])
        if self.uses_log_:
            if "amt_log" not in X.columns:
                raise ValueError("'amt_log' column missing from input.")
            return X["amt_log"].values.astype(float)
        else:
            if "amt" not in X.columns:
                raise ValueError("'amt' column missing from input.")
            return X["amt"].values.astype(float)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        """
        Return hard probability estimates: [P(legit), P(fraud)].
        Amounts above threshold → P(fraud)=1.0; below → P(fraud)=0.0.
        Note: hard probabilities — baseline is not calibrated.
        """
        amounts = self._get_amounts(X)
        fraud_prob = (amounts > self.threshold_).astype(float)
        return np.column_stack([1 - fraud_prob, fraud_prob])

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        """Predict class labels using the fitted threshold."""
        amounts = self._get_amounts(X)
        return (amounts > self.threshold_).astype(int)
