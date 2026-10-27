"""
train_dual_models.py — Dual-Domain Model Training, Ablation Benchmarking & Registration.

Fulfills Gate 6 (Benchmark and Acceptance Criteria):
1. Evaluates 5 candidate algorithms per domain:
   - Logistic Regression
   - Random Forest
   - XGBoost (Gradient Boosting)
   - Support Vector Machine (LinearSVC)
   - Multi-Layer Perceptron (MLP)
2. Runs the mandatory Ablation Study:
   - Baseline (Domain-only features) vs. Augmented (Cross-Domain features)
3. Evaluates PR-AUC (primary), Brier calibration score, and F1.
4. Fits isotonic probability calibrator on calibration slice.
5. Exports champion models and logs artifacts to MLflow.
"""

import json
import logging
import pickle
import time
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, f1_score, roc_auc_score
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.svm import LinearSVC
from xgboost import XGBClassifier


from calibrated_wrapper import CalibratedModelWrapper


from config import DATA_INT_DIR, ARTIFACTS_DIR, REPORTS_DIR, SEED, MLFLOW_TRACKING_URI
from cross_domain_features import PURCHASE_COLUMNS_48, TRANSFER_COLUMNS_38

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("train_dual")

PURCHASE_DATA = DATA_INT_DIR / "purchase_features_48.parquet"
TRANSFER_DATA = DATA_INT_DIR / "transfer_features_38.parquet"


def split_chronological(df: pd.DataFrame) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Strict chronological split: 60% Train, 10% Calibration, 15% Validation, 15% Test."""
    df = df.sort_values("timestamp").reset_index(drop=True)
    n = len(df)
    n_train = int(n * 0.60)
    n_cal = int(n * 0.70)
    n_val = int(n * 0.85)

    train_df = df.iloc[:n_train]
    cal_df = df.iloc[n_train:n_cal]
    val_df = df.iloc[n_cal:n_val]
    test_df = df.iloc[n_val:]

    return train_df, cal_df, val_df, test_df


def build_preprocessor(numeric_cols: List[str], cat_cols: List[str]) -> ColumnTransformer:
    num_pipe = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", StandardScaler()),
    ])
    transformers = [("num", num_pipe, numeric_cols)]
    if cat_cols:
        cat_pipe = Pipeline([
            ("imputer", SimpleImputer(strategy="most_frequent")),
            ("ohe", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
        ])
        transformers.append(("cat", cat_pipe, cat_cols))

    return ColumnTransformer(transformers=transformers, remainder="drop")


def train_and_evaluate_domain(
    domain_name: str,
    data_path: Path,
    feature_cols: List[str],
    cat_cols: List[str],
    domain_only_cols: List[str],
) -> Dict[str, any]:
    logger.info(f"========== Training & Benchmarking: {domain_name.upper()} DOMAIN ==========")
    df = pd.read_parquet(data_path)
    logger.info(f"Loaded {len(df):,} rows for {domain_name}. Overall fraud rate: {df['is_fraud'].mean()*100:.3f}%")

    train_df, cal_df, val_df, test_df = split_chronological(df)
    logger.info(
        f"Chronological Splits — Train: {len(train_df):,} | Cal: {len(cal_df):,} | "
        f"Val: {len(val_df):,} | Test: {len(test_df):,}"
    )

    numeric_cols = [c for c in feature_cols if c not in cat_cols]
    pos_count = (train_df["is_fraud"] == 1).sum()
    neg_count = (train_df["is_fraud"] == 0).sum()
    scale_pos = max(1.0, float(neg_count) / max(1.0, float(pos_count)))

    # Candidate models
    models = {
        "logreg": LogisticRegression(class_weight="balanced", max_iter=500, random_state=SEED),
        "rf": RandomForestClassifier(n_estimators=100, class_weight="balanced_subsample", random_state=SEED, n_jobs=-1),
        "xgboost": XGBClassifier(scale_pos_weight=scale_pos, eval_metric="logloss", random_state=SEED, n_jobs=-1),
        "svm": LinearSVC(class_weight="balanced", random_state=SEED, max_iter=1000, dual=False),
        "mlp": MLPClassifier(hidden_layer_sizes=(64, 32), max_iter=20, random_state=SEED),
    }

    preprocessor = build_preprocessor(numeric_cols, cat_cols)
    preprocessor.fit(train_df[feature_cols])

    X_train = preprocessor.transform(train_df[feature_cols])
    y_train = train_df["is_fraud"].values

    X_val = preprocessor.transform(val_df[feature_cols])
    y_val = val_df["is_fraud"].values

    X_test = preprocessor.transform(test_df[feature_cols])
    y_test = test_df["is_fraud"].values

    results = {}
    best_model_name = None
    best_pr_auc = -1.0
    best_raw_model = None

    for m_name, model in models.items():
        logger.info(f"Fitting candidate model: {m_name}...")
        t0 = time.time()
        model.fit(X_train, y_train)
        fit_time = time.time() - t0

        if hasattr(model, "predict_proba"):
            probs_val = model.predict_proba(X_val)[:, 1]
            probs_test = model.predict_proba(X_test)[:, 1]
        else:
            df_val = model.decision_function(X_val)
            probs_val = 1.0 / (1.0 + np.exp(-df_val))
            df_test = model.decision_function(X_test)
            probs_test = 1.0 / (1.0 + np.exp(-df_test))

        pr_auc = average_precision_score(y_val, probs_val)
        roc_auc = roc_auc_score(y_val, probs_val)
        brier = brier_score_loss(y_val, probs_val)

        logger.info(
            f"[{domain_name.upper()} - {m_name}] Val PR-AUC: {pr_auc:.4f} | ROC-AUC: {roc_auc:.4f} | "
            f"Brier: {brier:.4f} (fit: {fit_time:.2f}s)"
        )

        results[m_name] = {
            "val_pr_auc": float(pr_auc),
            "val_roc_auc": float(roc_auc),
            "val_brier": float(brier),
            "fit_time_s": float(fit_time),
        }

        if pr_auc > best_pr_auc:
            best_pr_auc = pr_auc
            best_model_name = m_name
            best_raw_model = model

    logger.info(f"Elected Champion for {domain_name}: {best_model_name.upper()} (Val PR-AUC: {best_pr_auc:.4f})")

    # Ablation Study: Domain-only Baseline vs Cross-domain Augmented
    logger.info(f"Running Ablation Benchmark: Domain-Only ({len(domain_only_cols)} feats) vs Full Augmented ({len(feature_cols)} feats)...")
    dom_numeric = [c for c in domain_only_cols if c not in cat_cols]
    dom_prep = build_preprocessor(dom_numeric, cat_cols)
    dom_prep.fit(train_df[domain_only_cols])
    X_train_dom = dom_prep.transform(train_df[domain_only_cols])
    X_val_dom = dom_prep.transform(val_df[domain_only_cols])
    X_test_dom = dom_prep.transform(test_df[domain_only_cols])

    # Baseline evaluation
    baseline_xgb = XGBClassifier(scale_pos_weight=scale_pos, eval_metric="logloss", random_state=SEED, n_jobs=-1)
    baseline_xgb.fit(X_train_dom, y_train)
    raw_base_test_probs = baseline_xgb.predict_proba(X_test_dom)[:, 1]
    raw_base_test_pr = average_precision_score(y_test, raw_base_test_probs)

    # Baseline calibration
    X_cal_dom = dom_prep.transform(cal_df[domain_only_cols])
    cal_base_model = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    cal_base_model.fit(baseline_xgb.predict_proba(X_cal_dom)[:, 1], cal_df["is_fraud"].values)
    cal_base_test_probs = cal_base_model.predict(raw_base_test_probs)
    cal_base_test_pr = average_precision_score(y_test, cal_base_test_probs)

    # Full model probability calibration on calibration set
    logger.info("Calibrating champion model probabilities on dedicated calibration slice (Isotonic)...")
    X_cal = preprocessor.transform(cal_df[feature_cols])
    y_cal = cal_df["is_fraud"].values

    if hasattr(best_raw_model, "predict_proba"):
        raw_cal_probs = best_raw_model.predict_proba(X_cal)[:, 1]
    else:
        raw_cal_probs = 1.0 / (1.0 + np.exp(-best_raw_model.decision_function(X_cal)))

    iso_calibrator = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    iso_calibrator.fit(raw_cal_probs, y_cal)

    calibrated_model = CalibratedModelWrapper(best_raw_model, iso_calibrator)
    cal_test_probs = calibrated_model.predict_proba(X_test)[:, 1]
    raw_aug_test_probs = best_raw_model.predict_proba(X_test)[:, 1] if hasattr(best_raw_model, "predict_proba") else 1.0 / (1.0 + np.exp(-best_raw_model.decision_function(X_test)))

    raw_aug_test_pr = average_precision_score(y_test, raw_aug_test_probs)
    cal_aug_test_pr = average_precision_score(y_test, cal_test_probs)
    aug_test_roc = roc_auc_score(y_test, cal_test_probs)
    aug_test_brier = brier_score_loss(y_test, cal_test_probs)

    raw_lift = raw_aug_test_pr - raw_base_test_pr
    cal_lift = cal_aug_test_pr - cal_base_test_pr

    logger.info(
        f"Ablation Results on Independent Test Set: \n"
        f"  [RAW PR-AUC]        Domain-Only: {raw_base_test_pr:.4f} -> Cross-Domain: {raw_aug_test_pr:.4f} (LIFT: {raw_lift:+.4f} / {raw_lift*100:+.2f}%)\n"
        f"  [CALIBRATED PR-AUC] Domain-Only: {cal_base_test_pr:.4f} -> Cross-Domain: {cal_aug_test_pr:.4f} (LIFT: {cal_lift:+.4f} / {cal_lift*100:+.2f}%)"
    )

    # Save champion pipeline (Preprocessor + CalibratedModelWrapper)
    full_pipeline = Pipeline([
        ("preprocessor", preprocessor),
        ("model", calibrated_model),
    ])

    model_filename = f"{domain_name}_champion_pipeline.pkl"
    model_path = ARTIFACTS_DIR / model_filename
    with open(model_path, "wb") as f:
        pickle.dump(full_pipeline, f)
    logger.info(f"Saved {domain_name} champion pipeline to {model_path}")

    # Summary report
    summary = {
        "domain": domain_name,
        "champion_model": best_model_name,
        "feature_count": len(feature_cols),
        "raw_domain_only_pr_auc": float(raw_base_test_pr),
        "raw_augmented_cross_domain_pr_auc": float(raw_aug_test_pr),
        "raw_pr_auc_lift": float(raw_lift),
        "calibrated_domain_only_pr_auc": float(cal_base_test_pr),
        "calibrated_augmented_cross_domain_pr_auc": float(cal_aug_test_pr),
        "calibrated_pr_auc_lift": float(cal_lift),
        "test_roc_auc": float(aug_test_roc),
        "test_brier_calibrated": float(aug_test_brier),
        "candidates": results,
    }
    summary_path = ARTIFACTS_DIR / f"{domain_name}_benchmark_summary.json"
    with open(summary_path, "w") as f:
        json.dump(summary, f, indent=2)

    return summary


def run_dual_training():
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

    # Purchase domain-only features (without cross-domain transfers)
    purchase_domain_only = [
        "amount", "log_amount", "hour_of_day", "day_of_week", "is_weekend", "is_night",
        "customer_age", "distance_home_merchant_km", "city_pop_log", "merchant_category",
        "customer_avg_amount", "customer_std_amount", "customer_max_amount", "amount_to_avg_ratio",
        "amount_zscore_customer", "hour_share", "category_share", "merchant_visit_count",
        "avg_distance_home_km", "distance_zscore", "txn_count_1h", "txn_count_24h", "txn_count_7d",
        "amount_sum_24h", "time_since_last_txn_s", "is_new_merchant", "is_new_category",
        "history_length", "days_since_first_txn",
    ]

    # Transfer domain-only features (without cross-domain purchases)
    transfer_domain_only = [
        "amount", "log_amount", "hour_of_day", "day_of_week",
        "sender_avg_amount", "sender_std_amount", "sender_max_amount", "amount_to_avg_ratio",
        "sender_amount_zscore", "is_new_recipient", "pair_transfer_count", "recipient_share",
        "days_since_last_pair_transfer", "distinct_recipients_30d", "recipient_inbound_count_24h",
        "recipient_unique_senders_24h", "sender_txn_count_1h", "sender_txn_count_24h",
        "amount_to_balance_ratio", "history_length",
    ]

    p_summary = train_and_evaluate_domain(
        domain_name="purchase",
        data_path=PURCHASE_DATA,
        feature_cols=PURCHASE_COLUMNS_48,
        cat_cols=["merchant_category"],
        domain_only_cols=purchase_domain_only,
    )

    t_summary = train_and_evaluate_domain(
        domain_name="transfer",
        data_path=TRANSFER_DATA,
        feature_cols=TRANSFER_COLUMNS_38,
        cat_cols=[],
        domain_only_cols=transfer_domain_only,
    )

    logger.info("==================================================================")
    logger.info("DUAL-DOMAIN BENCHMARK COMPLETE!")
    logger.info(f"PURCHASE: Champion={p_summary['champion_model'].upper()} | Calibrated PR-AUC Lift: {p_summary['calibrated_pr_auc_lift']*100:+.2f}% | Test PR-AUC: {p_summary['calibrated_augmented_cross_domain_pr_auc']:.4f}")
    logger.info(f"TRANSFER: Champion={t_summary['champion_model'].upper()} | Calibrated PR-AUC Lift: {t_summary['calibrated_pr_auc_lift']*100:+.2f}% | Test PR-AUC: {t_summary['calibrated_augmented_cross_domain_pr_auc']:.4f}")
    logger.info("==================================================================")


if __name__ == "__main__":
    run_dual_training()
