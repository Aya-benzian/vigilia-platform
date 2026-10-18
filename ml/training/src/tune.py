"""
tune.py — Optuna hyperparameter tuning for Member 1 (§6 of design v5).

One Optuna study per model. Objective = PR-AUC (average_precision_score)
on a 3-fold stratified CV over the tuning subsample of part A.

Rules:
- Tuning subsample is DISJOINT from comparison subsample (io.py).
- Best params are FROZEN before cv_compare.py runs.
- OPTUNA_N_TRIALS = 30 trials per model (same budget for all).
- Every study is logged as a run in MLflow under MLFLOW_EXP_TUNING.
- SVM uses a smaller subsample (SVM_SUBSAMPLE_N) — too slow otherwise.
"""

import logging
import os
from typing import Any, Dict, Optional

import mlflow
import numpy as np
import optuna
from sklearn.model_selection import StratifiedKFold, cross_val_score

from config import (
    SEED,
    OPTUNA_N_TRIALS,
    OPTUNA_TIMEOUT,
    OPTUNA_DIRECTION,
    MLFLOW_EXP_TUNING,
    SVM_SUBSAMPLE_N,
)
from models import make_logreg, make_random_forest, make_xgboost, make_svm, make_mlp
from preprocess import build_pipeline

logger = logging.getLogger(__name__)
optuna.logging.set_verbosity(optuna.logging.WARNING)

# Inner CV for objective — 3 folds is enough for tuning speed
TUNE_CV = StratifiedKFold(n_splits=3, shuffle=True, random_state=SEED)
SCORING  = "average_precision"


# ==============================================================================
# Shared objective helper
# ==============================================================================

def _cv_score(pipeline, X, y) -> float:
    """3-fold CV PR-AUC score for one trial."""
    scores = cross_val_score(
        pipeline, X, y,
        cv=TUNE_CV,
        scoring=SCORING,
        n_jobs=1,   # inner parallelism off — Optuna runs trials in parallel
        error_score=0.0,
    )
    return float(scores.mean())


# ==============================================================================
# Per-model tuning functions
# ==============================================================================

def tune_logreg(X_tune, y_tune) -> Dict[str, Any]:
    """Optuna study for Logistic Regression."""

    def objective(trial):
        penalty = trial.suggest_categorical("penalty", ["l1", "l2"])
        C       = trial.suggest_float("C", 1e-4, 1e2, log=True)
        solver  = "saga" if penalty == "l1" else "lbfgs"
        params  = dict(C=C, penalty=penalty, solver=solver)
        model   = make_logreg(params)
        pipe    = build_pipeline(model, model_type="logreg")
        return _cv_score(pipe, X_tune, y_tune)

    study = optuna.create_study(
        direction=OPTUNA_DIRECTION,
        sampler=optuna.samplers.TPESampler(seed=SEED),
        study_name="logreg_tuning",
    )
    study.optimize(objective, n_trials=OPTUNA_N_TRIALS, timeout=OPTUNA_TIMEOUT, show_progress_bar=False)
    best = study.best_params
    logger.info(f"LogReg best params: {best}  PR-AUC={study.best_value:.4f}")
    return best


def tune_rf(X_tune, y_tune) -> Dict[str, Any]:
    """Optuna study for Random Forest."""

    def objective(trial):
        n_estimators     = trial.suggest_int("n_estimators", 100, 500)
        max_depth        = trial.suggest_int("max_depth", 5, 30)
        min_samples_leaf = trial.suggest_int("min_samples_leaf", 1, 20)
        max_features     = trial.suggest_categorical("max_features", ["sqrt", "log2", 0.3])
        params = dict(
            n_estimators=n_estimators,
            max_depth=max_depth,
            min_samples_leaf=min_samples_leaf,
            max_features=max_features,
        )
        model = make_random_forest(params)
        pipe  = build_pipeline(model, model_type="rf")
        return _cv_score(pipe, X_tune, y_tune)

    study = optuna.create_study(
        direction=OPTUNA_DIRECTION,
        sampler=optuna.samplers.TPESampler(seed=SEED),
        study_name="rf_tuning",
    )
    study.optimize(objective, n_trials=OPTUNA_N_TRIALS, timeout=OPTUNA_TIMEOUT, show_progress_bar=False)
    best = study.best_params
    logger.info(f"RF best params: {best}  PR-AUC={study.best_value:.4f}")
    return best


def tune_xgboost(X_tune, y_tune, scale_pos_weight: float = 1.0) -> Dict[str, Any]:
    """Optuna study for XGBoost."""

    def objective(trial):
        params = dict(
            learning_rate    = trial.suggest_float("learning_rate", 1e-3, 0.3, log=True),
            max_depth        = trial.suggest_int("max_depth", 3, 10),
            n_estimators     = trial.suggest_int("n_estimators", 100, 500),
            subsample        = trial.suggest_float("subsample", 0.5, 1.0),
            colsample_bytree = trial.suggest_float("colsample_bytree", 0.5, 1.0),
            reg_lambda       = trial.suggest_float("reg_lambda", 1e-2, 10.0, log=True),
        )
        model = make_xgboost(params, scale_pos_weight=scale_pos_weight)
        pipe  = build_pipeline(model, model_type="xgboost")
        return _cv_score(pipe, X_tune, y_tune)

    study = optuna.create_study(
        direction=OPTUNA_DIRECTION,
        sampler=optuna.samplers.TPESampler(seed=SEED),
        study_name="xgboost_tuning",
    )
    study.optimize(objective, n_trials=OPTUNA_N_TRIALS, timeout=OPTUNA_TIMEOUT, show_progress_bar=False)
    best = study.best_params
    best["scale_pos_weight"] = scale_pos_weight  # always include for reproducibility
    logger.info(f"XGBoost best params: {best}  PR-AUC={study.best_value:.4f}")
    return best


def tune_svm(X_tune_small, y_tune_small) -> Dict[str, Any]:
    """
    Optuna study for RBF SVC on a SMALL subsample (≤ SVM_SUBSAMPLE_N rows).
    Caller must pass an already-subsampled dataset.
    """

    def objective(trial):
        C     = trial.suggest_float("C", 1e-2, 1e3, log=True)
        gamma = trial.suggest_categorical("gamma", ["scale", "auto"])
        params = dict(C=C, gamma=gamma)
        model  = make_svm(params, use_linear=False)
        pipe   = build_pipeline(model, model_type="svm")
        return _cv_score(pipe, X_tune_small, y_tune_small)

    study = optuna.create_study(
        direction=OPTUNA_DIRECTION,
        sampler=optuna.samplers.TPESampler(seed=SEED),
        study_name="svm_tuning",
    )
    study.optimize(objective, n_trials=OPTUNA_N_TRIALS, timeout=OPTUNA_TIMEOUT, show_progress_bar=False)
    best = study.best_params
    logger.info(f"SVM best params: {best}  PR-AUC={study.best_value:.4f}")
    return best


def tune_mlp(X_tune, y_tune) -> Dict[str, Any]:
    """Optuna study for MLP."""

    def objective(trial):
        n_layers        = trial.suggest_int("n_layers", 1, 3)
        n_units         = trial.suggest_categorical("n_units", [32, 64, 128, 256])
        alpha           = trial.suggest_float("alpha", 1e-5, 1e-1, log=True)
        learning_rate_init = trial.suggest_float("learning_rate_init", 1e-4, 1e-1, log=True)

        hidden_layer_sizes = tuple([n_units] * n_layers)
        params = dict(
            hidden_layer_sizes=hidden_layer_sizes,
            alpha=alpha,
            learning_rate_init=learning_rate_init,
        )
        model = make_mlp(params)
        pipe  = build_pipeline(model, model_type="mlp")
        return _cv_score(pipe, X_tune, y_tune)

    study = optuna.create_study(
        direction=OPTUNA_DIRECTION,
        sampler=optuna.samplers.TPESampler(seed=SEED),
        study_name="mlp_tuning",
    )
    study.optimize(objective, n_trials=OPTUNA_N_TRIALS, timeout=OPTUNA_TIMEOUT, show_progress_bar=False)
    best = study.best_params
    # Reconstruct hidden_layer_sizes tuple
    n_layers = best.pop("n_layers", 1)
    n_units  = best.pop("n_units", 64)
    best["hidden_layer_sizes"] = tuple([n_units] * n_layers)
    logger.info(f"MLP best params: {best}  PR-AUC={study.best_value:.4f}")
    return best


# ==============================================================================
# Run all studies
# ==============================================================================

def _get_cached_params(model_name: str) -> Optional[Dict[str, Any]]:
    """Try to retrieve already-tuned parameters from finished MLflow runs."""
    import ast
    try:
        from mlflow.tracking import MlflowClient
        client = MlflowClient()
        exp = client.get_experiment_by_name(MLFLOW_EXP_TUNING)
        if not exp:
            return None
        runs = client.search_runs(
            exp.experiment_id,
            filter_string=f"tags.mlflow.runName = 'tune_{model_name}' and attributes.status = 'FINISHED'",
            max_results=1,
        )
        if not runs:
            return None
        run = runs[0]
        params = {}
        prefix = f"{model_name}_"
        for k, v in run.data.params.items():
            if k.startswith(prefix):
                pk = k[len(prefix):]
                try:
                    val = ast.literal_eval(v)
                except Exception:
                    val = v
                params[pk] = val
        if params:
            logger.info(f"Loaded cached {model_name} best params from MLflow run {run.info.run_id}: {params}")
            return params
    except Exception as e:
        logger.debug(f"Could not load cached params for {model_name}: {e}")
    return None


def run_all_tuning(X_tune, y_tune, scale_pos_weight: float = 1.0) -> Dict[str, Dict]:
    """
    Run all five Optuna studies and return frozen hyperparameters for all models.

    Logs each study's best params and best value to MLflow under MLFLOW_EXP_TUNING.

    Args:
        X_tune: Tuning subsample feature DataFrame (from io.make_tune_and_compare_subsamples).
        y_tune: Target Series.
        scale_pos_weight: Negatives/positives ratio from part A (for XGBoost).

    Returns:
        Dict mapping model name -> best params dict.
        Example: {"logreg": {...}, "rf": {...}, "xgboost": {...}, "svm": {...}, "mlp": {...}}
    """
    mlflow.set_experiment(MLFLOW_EXP_TUNING)

    all_params = {}

    studies = [
        ("logreg",  lambda: tune_logreg(X_tune, y_tune)),
        ("rf",      lambda: tune_rf(X_tune, y_tune)),
        ("xgboost", lambda: tune_xgboost(X_tune, y_tune, scale_pos_weight)),
        ("mlp",     lambda: tune_mlp(X_tune, y_tune)),
    ]

    for model_name, tune_fn in studies:
        cached = _get_cached_params(model_name)
        if cached is not None:
            logger.info(f"=== {model_name} already tuned in MLflow -> reusing cached params ===")
            all_params[model_name] = cached
            continue

        logger.info(f"=== Tuning {model_name} ({OPTUNA_N_TRIALS} trials) ===")
        with mlflow.start_run(run_name=f"tune_{model_name}"):
            params = tune_fn()
            mlflow.log_params({f"{model_name}_{k}": v for k, v in params.items()})
            mlflow.log_param("model", model_name)
            mlflow.log_param("n_trials", OPTUNA_N_TRIALS)
            mlflow.log_param("seed", SEED)
        all_params[model_name] = params

    # SVM: use a smaller subsample
    cached_svm = _get_cached_params("svm")
    if cached_svm is not None:
        logger.info("=== svm already tuned in MLflow -> reusing cached params ===")
        all_params["svm"] = cached_svm
    else:
        logger.info(f"=== Tuning svm ({OPTUNA_N_TRIALS} trials, small subsample) ===")
        from sklearn.model_selection import train_test_split
        sample_n = min(SVM_SUBSAMPLE_N, len(X_tune) - 1)
        X_svm, _, y_svm, _ = train_test_split(
            X_tune, y_tune,
            train_size=sample_n,
            stratify=y_tune,
            random_state=SEED + 99,
        )
        with mlflow.start_run(run_name="tune_svm"):
            svm_params = tune_svm(X_svm, y_svm)
            mlflow.log_params({f"svm_{k}": v for k, v in svm_params.items()})
            mlflow.log_param("model", "svm")
            mlflow.log_param("svm_subsample_n", sample_n)
        all_params["svm"] = svm_params

    logger.info(f"=== All tuning complete. Best params: {all_params} ===")
    return all_params
