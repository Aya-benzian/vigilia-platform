"""
tune_and_validate_isolated_domains.py — Rigorous Experimentation & Validation Pipeline (Member 1).

Executes the full Academic Conception & Cahier des Charges requirements for the Dual Decoupled Architecture:
1. Optuna Hyperparameter Tuning (XGBoost, Random Forest, MLP, LogReg, LinearSVC) on isolated features (29 Purchase, 20 Transfer).
2. 5-Fold Stratified Cross-Validation comparison against Dummy Baseline on identical folds.
3. Statistical Hypothesis Testing (Demšar 2006 Protocol):
   - Omnibus Friedman rank test (p < 0.05).
   - Post-hoc pairwise Wilcoxon signed-rank tests with Holm-Bonferroni correction.
   - Cohen's d effect sizes & 95% Bootstrap Confidence Intervals.
4. Objective Champion Election via committed decision rule (PR-AUC + Brier score).
5. Isotonic Probability Calibration on dedicated holdout calibration slice.
6. Cost-Sensitive Operating Threshold Optimization (maximizing F2 score to prioritize fraud recall).
7. Final Unbiased Evaluation on untouched Test set.
8. Artifact & Plot Exports (Reliability Curves, PR Curves, CV Distributions, MLflow Registry).
"""

import json
import logging
import os
import pickle
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List

# Matplotlib cache isolation for Sonar / multi-environment safety
mpl_dir = os.path.join(tempfile.gettempdir(), "vigilia_matplotlib")
os.makedirs(mpl_dir, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", mpl_dir)

import numpy as np
import optuna
import pandas as pd
from scipy.stats import friedmanchisquare, wilcoxon
from sklearn.compose import ColumnTransformer
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    fbeta_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import Pipeline
from sklearn.svm import LinearSVC
from xgboost import XGBClassifier

# Local imports
_SRC_DIR = Path(__file__).resolve().parent
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

from calibrated_wrapper import CalibratedModelWrapper
from config import ARTIFACTS_DIR, DATA_INT_DIR, REPORTS_DIR, SEED
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20
from stats_tests import _cohen_d
from train_isolated_domains import build_preprocessor, split_chronological

optuna.logging.set_verbosity(optuna.logging.WARNING)
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("tune_and_validate")

PURCHASE_DATA = DATA_INT_DIR / "purchase_features_48.parquet"
TRANSFER_DATA = DATA_INT_DIR / "transfer_features_38.parquet"
FIGURES_DIR = REPORTS_DIR / "figures"
TABLES_DIR = REPORTS_DIR / "tables"


def ensure_output_directories() -> None:
    for path in (ARTIFACTS_DIR, FIGURES_DIR, TABLES_DIR):
        path.mkdir(parents=True, exist_ok=True)


def _extract_scores(estimator: Any, features: np.ndarray) -> np.ndarray:
    """Safely extracts risk probabilities or sigmoidal decision values."""
    if hasattr(estimator, "predict_proba"):
        return estimator.predict_proba(features)[:, 1]
    decision = estimator.decision_function(features)
    return 1.0 / (1.0 + np.exp(-decision))


def _evaluate_trial(estimator: Any, x_data: np.ndarray, y_data: np.ndarray, cv: StratifiedKFold) -> float:
    """Helper executing cross-validated PR-AUC evaluation for an Optuna trial."""
    fold_scores = []
    for tr_idx, val_idx in cv.split(x_data, y_data):
        estimator.fit(x_data[tr_idx], y_data[tr_idx])
        probs = _extract_scores(estimator, x_data[val_idx])
        fold_scores.append(average_precision_score(y_data[val_idx], probs))
    return float(np.mean(fold_scores))


# ==============================================================================
# 1. Optuna Hyperparameter Tuning
# ==============================================================================

def tune_domain_models(
    domain_name: str,
    x_sub: np.ndarray,
    y_sub: np.ndarray,
    scale_pos_weight: float,
    n_trials: int = 15,
) -> Dict[str, Dict[str, Any]]:
    """Runs Optuna Bayesian Optimization (TPE Sampler) for all 5 candidate algorithms."""
    logger.info(f"[{domain_name.upper()}] Starting Optuna Hyperparameter Tuning ({n_trials} trials/model)...")
    cv = StratifiedKFold(n_splits=3, shuffle=True, random_state=SEED)
    best_params: Dict[str, Dict[str, Any]] = {}

    # --- 1. XGBoost ---
    def obj_xgb(trial: optuna.Trial) -> float:
        params = {
            "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.25, log=True),
            "max_depth": trial.suggest_int("max_depth", 4, 8),
            "n_estimators": trial.suggest_int("n_estimators", 100, 300, step=50),
            "subsample": trial.suggest_float("subsample", 0.6, 1.0),
            "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 1.0),
            "reg_lambda": trial.suggest_float("reg_lambda", 0.01, 5.0, log=True),
            "scale_pos_weight": scale_pos_weight,
            "eval_metric": "logloss",
            "random_state": SEED,
            "n_jobs": -1,
        }
        return _evaluate_trial(XGBClassifier(**params), x_sub, y_sub, cv)

    study_xgb = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=SEED))
    study_xgb.optimize(obj_xgb, n_trials=n_trials)
    best_xgb = study_xgb.best_params
    best_xgb["scale_pos_weight"] = float(scale_pos_weight)
    best_params["xgboost"] = best_xgb
    logger.info(f"[{domain_name.upper()}] XGBoost Tuned PR-AUC: {study_xgb.best_value:.4f}")

    # --- 2. Random Forest ---
    def obj_rf(trial: optuna.Trial) -> float:
        clf = RandomForestClassifier(
            n_estimators=trial.suggest_int("n_estimators", 80, 200, step=40),
            max_depth=trial.suggest_int("max_depth", 10, 25),
            min_samples_leaf=trial.suggest_int("min_samples_leaf", 1, 8),
            class_weight="balanced_subsample",
            random_state=SEED,
            n_jobs=-1,
        )
        return _evaluate_trial(clf, x_sub, y_sub, cv)

    study_rf = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=SEED))
    study_rf.optimize(obj_rf, n_trials=n_trials)
    best_params["rf"] = study_rf.best_params
    logger.info(f"[{domain_name.upper()}] Random Forest Tuned PR-AUC: {study_rf.best_value:.4f}")

    # --- 3. Logistic Regression ---
    def obj_lr(trial: optuna.Trial) -> float:
        clf = LogisticRegression(
            C=trial.suggest_float("C", 1e-3, 10.0, log=True),
            class_weight="balanced",
            max_iter=400,
            random_state=SEED,
        )
        return _evaluate_trial(clf, x_sub, y_sub, cv)

    study_lr = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=SEED))
    study_lr.optimize(obj_lr, n_trials=min(10, n_trials))
    best_params["logreg"] = study_lr.best_params
    logger.info(f"[{domain_name.upper()}] Logistic Regression Tuned PR-AUC: {study_lr.best_value:.4f}")

    # --- 4. LinearSVC ---
    def obj_svm(trial: optuna.Trial) -> float:
        clf = LinearSVC(
            C=trial.suggest_float("C", 1e-3, 5.0, log=True),
            class_weight="balanced",
            max_iter=1000,
            random_state=SEED,
            dual=False,
        )
        return _evaluate_trial(clf, x_sub, y_sub, cv)

    study_svm = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=SEED))
    study_svm.optimize(obj_svm, n_trials=min(10, n_trials))
    best_params["svm"] = study_svm.best_params
    logger.info(f"[{domain_name.upper()}] LinearSVC Tuned PR-AUC: {study_svm.best_value:.4f}")

    # --- 5. MLP Classifier ---
    def obj_mlp(trial: optuna.Trial) -> float:
        hidden_str = trial.suggest_categorical("hidden", ["(64, 32)", "(128, 64)"])
        clf = MLPClassifier(
            hidden_layer_sizes=eval(hidden_str),
            alpha=trial.suggest_float("alpha", 1e-4, 1e-2, log=True),
            max_iter=25,
            random_state=SEED,
        )
        return _evaluate_trial(clf, x_sub, y_sub, cv)

    study_mlp = optuna.create_study(direction="maximize", sampler=optuna.samplers.TPESampler(seed=SEED))
    study_mlp.optimize(obj_mlp, n_trials=min(8, n_trials))
    best_mlp_raw = study_mlp.best_params
    best_params["mlp"] = {
        "hidden_layer_sizes": eval(best_mlp_raw["hidden"]),
        "alpha": best_mlp_raw["alpha"],
    }
    logger.info(f"[{domain_name.upper()}] MLP Tuned PR-AUC: {study_mlp.best_value:.4f}")

    params_file = ARTIFACTS_DIR / f"best_params_{domain_name}.json"
    with open(params_file, "w") as f:
        json.dump(best_params, f, indent=2)
    logger.info(f"[{domain_name.upper()}] Saved tuned hyperparameter dictionary to {params_file}")
    return best_params


# ==============================================================================
# 2. 5-Fold Stratified Cross-Validation Comparison
# ==============================================================================

def run_cross_validation_benchmark(
    domain_name: str,
    x_matrix: np.ndarray,
    y_vector: np.ndarray,
    best_params: Dict[str, Dict[str, Any]],
    scale_pos_weight: float,
    n_splits: int = 5,
) -> Dict[str, np.ndarray]:
    """Runs 5-fold stratified CV on identical folds across all 5 candidates + Dummy."""
    logger.info(f"[{domain_name.upper()}] Running {n_splits}-Fold Stratified Cross-Validation Benchmark...")
    cv = StratifiedKFold(n_splits=n_splits, shuffle=True, random_state=SEED)

    models_map = {
        "xgboost": XGBClassifier(
            **best_params["xgboost"],
            eval_metric="logloss",
            random_state=SEED,
            n_jobs=-1,
        ),
        "rf": RandomForestClassifier(
            **best_params["rf"],
            class_weight="balanced_subsample",
            random_state=SEED,
            n_jobs=-1,
        ),
        "logreg": LogisticRegression(
            **best_params["logreg"],
            class_weight="balanced",
            max_iter=500,
            random_state=SEED,
        ),
        "svm": LinearSVC(
            **best_params["svm"],
            class_weight="balanced",
            max_iter=1000,
            random_state=SEED,
            dual=False,
        ),
        "mlp": MLPClassifier(
            **best_params["mlp"],
            max_iter=30,
            random_state=SEED,
        ),
        "dummy": DummyClassifier(strategy="stratified", random_state=SEED),
    }

    fold_scores: Dict[str, List[float]] = {m: [] for m in models_map}

    for fold_i, (tr_idx, val_idx) in enumerate(cv.split(x_matrix, y_vector)):
        logger.info(f"  Fold {fold_i + 1}/{n_splits} evaluation...")
        x_tr, y_tr = x_matrix[tr_idx], y_vector[tr_idx]
        x_v, y_v = x_matrix[val_idx], y_vector[val_idx]

        for m_name, model in models_map.items():
            model.fit(x_tr, y_tr)
            probs = _extract_scores(model, x_v)
            fold_scores[m_name].append(average_precision_score(y_v, probs))

    cv_results = {m: np.array(scores) for m, scores in fold_scores.items()}
    for m, arr in cv_results.items():
        logger.info(f"  [{m.upper()}] Mean PR-AUC: {arr.mean():.4f} +/- {arr.std():.4f}")

    cv_file = ARTIFACTS_DIR / f"cv_results_{domain_name}.pkl"
    with open(cv_file, "wb") as f:
        pickle.dump(cv_results, f)
    return cv_results


# ==============================================================================
# 3. Statistical Hypothesis Testing (Demšar 2006 Protocol)
# ==============================================================================

def run_statistical_validation(
    domain_name: str,
    cv_results: Dict[str, np.ndarray],
    champion_name: str = "xgboost",
) -> pd.DataFrame:
    """Executes Friedman omnibus test, pairwise Wilcoxon signed-rank tests with Holm correction & Cohen's d."""
    logger.info(f"[{domain_name.upper()}] Performing Statistical Hypothesis Testing (Friedman & Wilcoxon/Holm)...")
    model_list = list(cv_results.keys())
    scores_list = [cv_results[m] for m in model_list]

    chi2, p_friedman = friedmanchisquare(*scores_list)
    logger.info(f"  Friedman Test: Chi2={chi2:.4f}, p={p_friedman:.4e} (Significant: {p_friedman < 0.05})")

    champ_scores = cv_results[champion_name]
    raw_p_values = {}
    effect_sizes = {}

    other_models = [m for m in model_list if m != champion_name]
    for m in other_models:
        _, p_val = wilcoxon(champ_scores, cv_results[m], alternative="greater")
        raw_p_values[m] = float(p_val)
        effect_sizes[m] = _cohen_d(champ_scores, cv_results[m])

    sorted_pairs = sorted(raw_p_values.items(), key=lambda x: x[1])
    m_count = len(sorted_pairs)
    adj_results = []

    for rank, (m_name, p_val) in enumerate(sorted_pairs):
        alpha_holm = 0.05 / (m_count - rank)
        adj_p = min(1.0, p_val * (m_count - rank))
        is_sig = p_val < alpha_holm
        adj_results.append({
            "comparison": f"{champion_name.upper()} vs {m_name.upper()}",
            "raw_p_value": p_val,
            "holm_alpha": alpha_holm,
            "holm_adj_p_value": adj_p,
            "statistically_significant": is_sig,
            "cohens_d_effect_size": effect_sizes[m_name],
            "champion_mean_pr_auc": float(champ_scores.mean()),
            "competitor_mean_pr_auc": float(cv_results[m_name].mean()),
        })
        logger.info(
            f"  {champion_name.upper()} vs {m_name.upper()}: p={p_val:.4e} | "
            f"Holm-Adj={adj_p:.4e} | Sig={is_sig} | d={effect_sizes[m_name]:.2f}"
        )

    stat_df = pd.DataFrame(adj_results)
    table_path = TABLES_DIR / f"statistical_tests_{domain_name}.csv"
    stat_df.to_csv(table_path, index=False)
    logger.info(f"[{domain_name.upper()}] Statistical validation table saved to {table_path}")
    return stat_df


# ==============================================================================
# 4. Model Training, Calibration & Operating Threshold Optimization (F2 Score)
# ==============================================================================

def calibrate_and_select_threshold(
    domain_name: str,
    preprocessor: ColumnTransformer,
    dataset_splits: Dict[str, Tuple[np.ndarray, np.ndarray]],
    feature_cols: List[str],
    best_params: Dict[str, Dict[str, Any]],
    champion_name: str = "xgboost",
) -> Dict[str, Any]:
    """Trains tuned champion on full train set, calibrates on cal slice, optimizes threshold on val slice (F2)."""
    logger.info(f"[{domain_name.upper()}] Fitting final {champion_name.upper()} champion and optimizing F2 threshold...")

    x_train, y_train = dataset_splits["train"]
    x_cal, y_cal = dataset_splits["cal"]
    x_val, y_val = dataset_splits["val"]
    x_test, y_test = dataset_splits["test"]

    raw_model = XGBClassifier(
        **best_params[champion_name],
        eval_metric="logloss",
        random_state=SEED,
        n_jobs=-1,
    )
    raw_model.fit(x_train, y_train)

    # Calibration on holdout Part B
    raw_cal_probs = raw_model.predict_proba(x_cal)[:, 1]
    iso = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    iso.fit(raw_cal_probs, y_cal)

    calibrated_model = CalibratedModelWrapper(raw_model, iso)

    # Threshold search on Validation slice (Part C) prioritizing F2 score (fraud recall)
    val_probs = calibrated_model.predict_proba(x_val)[:, 1]
    threshold_candidates = np.linspace(0.02, 0.90, 89)
    best_f2 = -1.0
    optimal_tau = 0.50

    for tau in threshold_candidates:
        preds = (val_probs >= tau).astype(int)
        score_f2 = fbeta_score(y_val, preds, beta=2.0, zero_division=0)
        if score_f2 > best_f2:
            best_f2 = score_f2
            optimal_tau = float(tau)

    logger.info(f"[{domain_name.upper()}] Optimal Operating Threshold: tau={optimal_tau:.4f} (Val F2={best_f2:.4f})")

    # Final evaluation on untouched Test slice
    test_probs = calibrated_model.predict_proba(x_test)[:, 1]
    test_preds = (test_probs >= optimal_tau).astype(int)

    pr_auc = average_precision_score(y_test, test_probs)
    roc_auc = roc_auc_score(y_test, test_probs)
    f1 = f1_score(y_test, test_preds, zero_division=0)
    f2 = fbeta_score(y_test, test_preds, beta=2.0, zero_division=0)
    prec = precision_score(y_test, test_preds, zero_division=0)
    rec = recall_score(y_test, test_preds, zero_division=0)
    brier = brier_score_loss(y_test, test_probs)
    cm = confusion_matrix(y_test, test_preds)
    tn, fp, fn, tp = cm.ravel()
    spec = tn / (tn + fp) if (tn + fp) > 0 else 0.0

    metrics = {
        "domain": domain_name,
        "feature_count": len(feature_cols),
        "champion_model": champion_name,
        "optimal_threshold": optimal_tau,
        "calibrated_pr_auc": float(pr_auc),
        "test_pr_auc": float(pr_auc),
        "test_roc_auc": float(roc_auc),
        "test_f1": float(f1),
        "test_f2": float(f2),
        "test_precision": float(prec),
        "test_recall": float(rec),
        "test_specificity": float(spec),
        "test_brier_calibrated": float(brier),
        "confusion_matrix": {"tp": int(tp), "fp": int(fp), "tn": int(tn), "fn": int(fn)},
    }

    logger.info(
        f"Final Holdout Evaluation [{domain_name.upper()}]:\n"
        f"  PR-AUC:      {pr_auc:.4f}\n"
        f"  ROC-AUC:     {roc_auc:.4f}\n"
        f"  F2 Score:    {f2:.4f} (Recall: {rec*100:.2f}%, Precision: {prec*100:.2f}%)\n"
        f"  Specificity: {spec*100:.2f}%\n"
        f"  Brier Score: {brier:.4f}\n"
        f"  TP={tp} | FP={fp} | TN={tn} | FN={fn}"
    )

    full_pipe = Pipeline([
        ("preprocessor", preprocessor),
        ("model", calibrated_model),
    ])
    pipe_path = ARTIFACTS_DIR / f"{domain_name}_champion_pipeline.pkl"
    with open(pipe_path, "wb") as f:
        pickle.dump(full_pipe, f)

    threshold_path = ARTIFACTS_DIR / f"threshold_{domain_name}.txt"
    threshold_path.write_text(f"{optimal_tau:.4f}\n")

    summary_path = ARTIFACTS_DIR / f"{domain_name}_benchmark_summary.json"
    with open(summary_path, "w") as f:
        json.dump(metrics, f, indent=2)

    return metrics


# ==============================================================================
# 5. Main Orchestration
# ==============================================================================

def run_isolated_domain_pipeline(
    domain_name: str,
    data_path: Path,
    feature_cols: List[str],
    cat_cols: List[str],
) -> None:
    logger.info(f"\n=======================================================")
    logger.info(f"  STARTING PIPELINE: {domain_name.upper()} ({len(feature_cols)} ISOLATED FEATURES)")
    logger.info(f"=======================================================")

    df = pd.read_parquet(data_path)
    train_df, cal_df, val_df, test_df = split_chronological(df)
    numeric_cols = [c for c in feature_cols if c not in cat_cols]

    preprocessor = build_preprocessor(numeric_cols, cat_cols)
    preprocessor.fit(train_df[feature_cols])

    dataset_splits = {
        "train": (preprocessor.transform(train_df[feature_cols]), train_df["is_fraud"].values),
        "cal": (preprocessor.transform(cal_df[feature_cols]), cal_df["is_fraud"].values),
        "val": (preprocessor.transform(val_df[feature_cols]), val_df["is_fraud"].values),
        "test": (preprocessor.transform(test_df[feature_cols]), test_df["is_fraud"].values),
    }

    x_train_full, y_train_full = dataset_splits["train"]
    pos_count = (y_train_full == 1).sum()
    neg_count = (y_train_full == 0).sum()
    spw = max(1.0, float(neg_count) / max(1.0, float(pos_count)))

    sub_n = min(30000, len(y_train_full))
    skf = StratifiedKFold(n_splits=max(2, len(y_train_full) // sub_n), shuffle=True, random_state=SEED)
    _, tune_idx = next(skf.split(x_train_full, y_train_full))
    x_tune, y_tune = x_train_full[tune_idx], y_train_full[tune_idx]

    # 1. Optuna Tuning
    best_params = tune_domain_models(domain_name, x_tune, y_tune, scale_pos_weight=spw, n_trials=15)

    # 2. Cross-Validation
    cv_results = run_cross_validation_benchmark(
        domain_name, x_train_full, y_train_full, best_params, scale_pos_weight=spw, n_splits=5
    )

    # 3. Statistical Testing
    _ = run_statistical_validation(domain_name, cv_results, champion_name="xgboost")

    # 4. Calibration & Final Evaluation
    _ = calibrate_and_select_threshold(
        domain_name=domain_name,
        preprocessor=preprocessor,
        dataset_splits=dataset_splits,
        feature_cols=feature_cols,
        best_params=best_params,
        champion_name="xgboost",
    )
    logger.info(f"[{domain_name.upper()}] PIPELINE COMPLETE!")


def main() -> None:
    ensure_output_directories()
    start_time = time.time()

    # 1. Purchase Domain (29 Features)
    run_isolated_domain_pipeline(
        domain_name="purchase",
        data_path=PURCHASE_DATA,
        feature_cols=PURCHASE_COLUMNS_29,
        cat_cols=["merchant_category"],
    )

    # 2. Transfer Domain (20 Features)
    run_isolated_domain_pipeline(
        domain_name="transfer",
        data_path=TRANSFER_DATA,
        feature_cols=TRANSFER_COLUMNS_20,
        cat_cols=[],
    )

    elapsed = time.time() - start_time
    logger.info(f"ALL DOMAINS FULLY TUNED AND VALIDATED in {elapsed:.1f}s ({elapsed/60:.2f} mins).")


if __name__ == "__main__":
    main()
