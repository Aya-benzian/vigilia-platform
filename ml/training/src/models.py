"""
models.py — Model factory for the five required classifiers (Member 1).

Each function returns an unfitted sklearn-compatible estimator.
After Optuna tuning (tune.py), best params are passed via the `params` argument.
All models are wrapped in a Pipeline by preprocess.build_pipeline().
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
    Logistic Regression with class_weight='balanced'.

    Tunable: C, penalty, solver.
    """
    defaults = dict(
        C=1.0,
        penalty="l2",
        solver="lbfgs",
        max_iter=1000,
        class_weight="balanced",
        n_jobs=-1,
    )
    if params:
        clean = {k: v for k, v in params.items() if k != "random_state"}
        if clean.get("penalty") == "l1":
            clean.setdefault("solver", "saga")
        else:
            clean.setdefault("solver", "lbfgs")
        defaults.update(clean)

    logger.debug(f"make_logreg: {defaults}")
    return LogisticRegression(**defaults, random_state=SEED)


def make_random_forest(params: Optional[Dict[str, Any]] = None) -> RandomForestClassifier:
    """
    Random Forest with class_weight='balanced_subsample'.

    Tunable: n_estimators, max_depth, min_samples_leaf, max_features.
    """
    defaults = dict(
        n_estimators=300,
        max_depth=None,
        min_samples_leaf=1,
        max_features="sqrt",
        class_weight="balanced_subsample",
        n_jobs=-1,
    )
    if params:
        defaults.update({k: v for k, v in params.items() if k != "random_state"})

    logger.debug(f"make_random_forest: {defaults}")
    return RandomForestClassifier(**defaults, random_state=SEED)


def make_xgboost(
    params: Optional[Dict[str, Any]] = None,
    scale_pos_weight: float = 1.0,
) -> XGBClassifier:
    """
    XGBoost with tree_method='hist' for speed on large tabular data.

    scale_pos_weight is computed from part A (negatives/positives ratio).
    Tunable: learning_rate, max_depth, n_estimators, subsample,
             colsample_bytree, reg_lambda.
    """
    defaults = dict(
        learning_rate=0.1,
        max_depth=6,
        n_estimators=300,
        subsample=0.8,
        colsample_bytree=0.8,
        reg_lambda=1.0,
        scale_pos_weight=scale_pos_weight,
        tree_method="hist",
        n_jobs=-1,
        eval_metric="aucpr",
        verbosity=0,
        enable_categorical=True,
    )
    if params:
        defaults.update({k: v for k, v in params.items() if k != "random_state"})

    logger.debug(f"make_xgboost: scale_pos_weight={scale_pos_weight}")
    return XGBClassifier(**defaults, random_state=SEED)


def make_svm(
    params: Optional[Dict[str, Any]] = None,
    use_linear: bool = False,
) -> SVC | LinearSVC:
    """
    Support Vector Machine.

    use_linear=False: RBF SVC on SVM_SUBSAMPLE_N rows (~30k).
                      No predict_proba — calibrate.py wraps it.
    use_linear=True:  LinearSVC, faster, same calibration path.

    Tunable: C, gamma (RBF) or C (LinearSVC).
    """
    if use_linear:
        defaults = dict(
            C=1.0,
            class_weight="balanced",
            max_iter=2000,
        )
        if params:
            defaults.update({k: v for k, v in params.items() if k != "random_state"})
        logger.debug(f"make_svm (LinearSVC): {defaults}")
        return LinearSVC(**defaults, random_state=SEED)
    else:
        defaults = dict(
            C=1.0,
            kernel="rbf",
            gamma="scale",
            class_weight="balanced",
            probability=False,  # CalibratedClassifierCV will add probabilities
        )
        if params:
            defaults.update({k: v for k, v in params.items() if k != "random_state"})
        logger.debug(f"make_svm (RBF SVC): {defaults}")
        return SVC(**defaults, random_state=SEED)


def make_mlp(params: Optional[Dict[str, Any]] = None) -> MLPClassifier:
    """
    Multi-Layer Perceptron with early stopping.

    Tunable: hidden_layer_sizes, alpha, learning_rate_init.
    """
    defaults = dict(
        hidden_layer_sizes=(128, 64),
        alpha=1e-4,
        learning_rate_init=1e-3,
        max_iter=200,
        early_stopping=True,
        validation_fraction=0.1,
    )
    if params:
        defaults.update({k: v for k, v in params.items() if k != "random_state"})

    logger.debug(f"make_mlp: {defaults}")
    return MLPClassifier(**defaults, random_state=SEED)


# ==============================================================================
# Model registry
# ==============================================================================
MODEL_FACTORIES = {
    "logreg":  make_logreg,
    "rf":      make_random_forest,
    "xgboost": make_xgboost,
    "svm":     make_svm,
    "mlp":     make_mlp,
}
