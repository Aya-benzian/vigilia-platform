"""
baselines.py — Trivial baseline models for Member 1.

Two baselines are required by the spec (§6 of design v5):
1. DummyClassifier (prior) — always predicts the majority class or random.
2. One-rule baseline — flags a transaction as fraud if `amt` exceeds a threshold
   chosen on part A. Proves that ML adds value beyond a simple amount rule.

Both baselines must be beaten by the real models to justify the ML system.
Their scores are logged to MLflow under the fraud-sparkov-compare experiment.
"""

import logging

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, ClassifierMixin
from sklearn.dummy import DummyClassifier
from sklearn.utils.validation import check_is_fitted

logger = logging.getLogger(__name__)


# ==============================================================================
# Baseline 1: DummyClassifier (sklearn built-in)
# ==============================================================================

def make_dummy_classifier(strategy: str = "stratified", random_state: int = 42):
    """
    Return a DummyClassifier with the given strategy.

    Strategies to consider:
        "stratified" : samples from the training class distribution (default).
        "most_frequent" : always predicts majority class (all legitimate).
        "prior" : predicts class probabilities equal to training distribution.

    Args:
        strategy: DummyClassifier strategy.
        random_state: Reproducibility seed.

    Returns:
        Unfitted DummyClassifier instance.
    """
    raise NotImplementedError


# ==============================================================================
# Baseline 2: One-rule amount threshold classifier
# ==============================================================================

class AmountThresholdClassifier(BaseEstimator, ClassifierMixin):
    """
    One-rule baseline: predict FRAUD if transaction amount exceeds a threshold.

    The threshold is chosen on part A to maximize F1 score on the training data.
    It is a deliberately simple rule — the point is to show that a real model
    does meaningfully better than a single-feature rule.

    Attributes:
        threshold_: Best amount threshold found during fit().
        classes_: [0, 1]
    """

    def __init__(self):
        pass

    def fit(self, X: pd.DataFrame, y: pd.Series) -> "AmountThresholdClassifier":
        """
        Find the amount threshold that maximizes F1 on the training data.

        Searches over candidate percentiles of the `amt` column.
        If `amt` is not present (Pipeline has already transformed it),
        uses `amt_log` instead — document which one was used.

        Args:
            X: Feature DataFrame. Must contain `amt` or `amt_log`.
            y: Binary target (0/1).

        Returns:
            self (fitted).
        """
        raise NotImplementedError

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        """
        Return probability estimates: [P(legitimate), P(fraud)].

        Transactions above the threshold get P(fraud)=1.0; below get P(fraud)=0.0.
        Note: these are hard 0/1 probabilities — the baseline is not calibrated.

        Args:
            X: Feature DataFrame.

        Returns:
            Array of shape (n_samples, 2).
        """
        raise NotImplementedError

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        """
        Predict class labels using the fitted threshold.

        Args:
            X: Feature DataFrame.

        Returns:
            Binary array of shape (n_samples,).
        """
        raise NotImplementedError
