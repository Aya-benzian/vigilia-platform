"""
models.py — Model factory for the five required classifiers (Member 1).

This module defines one factory function per model. Each function returns an
unfitted sklearn-compatible estimator with hyperparameters frozen after tuning.

Rules:
- Hyperparameters start as defaults here. After Optuna tuning (tune.py), the
  best hyperparameters are passed back here via the `params` argument.
- Models are always wrapped in a Pipeline by preprocess.py (build_pipeline()).
- SVM has no usable predict_proba — uses decision_function during CV scoring;
  calibrate.py wraps it with CalibratedClassifierCV on part B.
- XGBoost uses tree_method="hist" for speed on large data.
- Seed 42 is applied to all models that accept random_state.

Imbalance handling per model (§5 of design v5):
    LogReg   → class_weight="balanced"
    RF       → class_weight="balanced_subsample"
    XGBoost  → scale_pos_weight=negatives/positives (passed at fit time)
    SVM      → class_weight="balanced"
    MLP      → early_stopping=True with a validation fraction
"""

import logging
from typing import Any, Dict, Optional

from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.svm import SVC, LinearSVC
from sklearn.neural_network import MLPClassifier
from xgboost import XGBClassifier

from config import SEED

logger = logging.getLogger(__name__)


def make_logreg(params: Optional[Dict[str, Any]] = None) -> LogisticRegression:
    """
    Logistic Regression classifier.

    Default hyperparameters (overridden by Optuna in tune.py):
        C=1.0, penalty="l2", solver="lbfgs", max_iter=1000

    Tunable hyperparameters (§6 of design v5):
        C       : regularization inverse strength
        penalty : "l1" or "l2" (solver must match)
        solver  : "lbfgs", "saga" (required for l1)

    Args:
        params: Dict of hyperparameters to override defaults. Pass None for defaults.

    Returns:
        Unfitted LogisticRegression with class_weight="balanced".
    """
    raise NotImplementedError


def make_random_forest(params: Optional[Dict[str, Any]] = None) -> RandomForestClassifier:
    """
    Random Forest classifier.

    Default hyperparameters:
        n_estimators=300, max_depth=None, min_samples_leaf=1, max_features="sqrt"

    Tunable hyperparameters:
        n_estimators, max_depth, min_samples_leaf, max_features

    Args:
        params: Dict of hyperparameters to override defaults.

    Returns:
        Unfitted RandomForestClassifier with class_weight="balanced_subsample".
    """
    raise NotImplementedError


def make_xgboost(
    params: Optional[Dict[str, Any]] = None,
    scale_pos_weight: float = 1.0,
) -> XGBClassifier:
    """
    XGBoost gradient-boosted tree classifier.

    Uses tree_method="hist" for speed on large tabular data (required on Sparkov ~1.3M rows).

    Default hyperparameters:
        learning_rate=0.1, max_depth=6, n_estimators=300,
        subsample=0.8, colsample_bytree=0.8, reg_lambda=1.0

    Tunable hyperparameters:
        learning_rate, max_depth, n_estimators, subsample,
        colsample_bytree, reg_lambda, scale_pos_weight

    Args:
        params: Dict of hyperparameters to override defaults.
        scale_pos_weight: Ratio negatives/positives. Computed from part A and passed here.

    Returns:
        Unfitted XGBClassifier.
    """
    raise NotImplementedError


def make_svm(
    params: Optional[Dict[str, Any]] = None,
    use_linear: bool = False,
) -> SVC | LinearSVC:
    """
    Support Vector Machine classifier.

    Two variants (§4 of design v5 — compute reality):
        use_linear=False : RBF SVC on a small subsample (SVM_SUBSAMPLE_N = 30k rows).
                           Has predict_proba=False — uses decision_function for scoring.
                           Calibrator (calibrate.py) will wrap it to get probabilities.
        use_linear=True  : LinearSVC on the full comparison subsample.
                           Faster, but still has no predict_proba — same calibration path.

    IMPORTANT: Document which variant you used and confirm with the professor
    if the spec expects RBF specifically.

    Default hyperparameters (RBF):
        C=1.0, gamma="scale"

    Tunable hyperparameters:
        C, gamma (RBF) or C (LinearSVC)

    Args:
        params: Dict of hyperparameters to override defaults.
        use_linear: If True, return LinearSVC instead of RBF SVC.

    Returns:
        Unfitted SVC or LinearSVC with class_weight="balanced".
    """
    raise NotImplementedError


def make_mlp(params: Optional[Dict[str, Any]] = None) -> MLPClassifier:
    """
    Multi-Layer Perceptron classifier.

    Uses early_stopping=True with a 10% validation fraction to prevent overfitting
    without needing a separate validation set.

    Default hyperparameters:
        hidden_layer_sizes=(128, 64), alpha=1e-4, learning_rate_init=1e-3,
        max_iter=200, early_stopping=True, validation_fraction=0.1

    Tunable hyperparameters:
        hidden_layer_sizes (n layers, n units), alpha, learning_rate_init

    Args:
        params: Dict of hyperparameters to override defaults.

    Returns:
        Unfitted MLPClassifier.
    """
    raise NotImplementedError


# ==============================================================================
# Model registry — maps name → factory function
# ==============================================================================
MODEL_FACTORIES = {
    "logreg":   make_logreg,
    "rf":        make_random_forest,
    "xgboost":   make_xgboost,
    "svm":       make_svm,
    "mlp":       make_mlp,
}
