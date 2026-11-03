"""
train_isolated_domains.py — Training & Calibration for Strictly Decoupled (Domain-Only) Models.

Supervisor Directive:
Strict Domain Isolation:
- Purchase Domain: exactly 29 domain-only features from Sparkov (Zero transfer features).
- Transfer Domain: exactly 20 domain-only features from PaySim (Zero purchase features).
- Fully independent training, calibration, artifact export, and golden parity rows.
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
from config import DATA_INT_DIR, ARTIFACTS_DIR, SEED
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("train_isolated")

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


def train_and_export_isolated_domain(
    domain_name: str,
    data_path: Path,
    feature_cols: List[str],
    cat_cols: List[str],
) -> Dict[str, any]:
    logger.info(f"========== Training & Calibrating Isolated: {domain_name.upper()} DOMAIN ({len(feature_cols)} features) ==========")
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
        else:
            df_val = model.decision_function(X_val)
            probs_val = 1.0 / (1.0 + np.exp(-df_val))

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

    # Fit Probability Calibrator on Calibration Slice
    logger.info("Calibrating champion model probabilities on dedicated calibration slice (Isotonic)...")
    X_cal = preprocessor.transform(cal_df[feature_cols])
    y_cal = cal_df["is_fraud"].values

    if hasattr(best_raw_model, "predict_proba"):
        raw_cal_probs = best_raw_model.predict_proba(X_cal)[:, 1]
        raw_test_probs = best_raw_model.predict_proba(X_test)[:, 1]
    else:
        raw_cal_probs = 1.0 / (1.0 + np.exp(-best_raw_model.decision_function(X_cal)))
        raw_test_probs = 1.0 / (1.0 + np.exp(-best_raw_model.decision_function(X_test)))

    iso_calibrator = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    iso_calibrator.fit(raw_cal_probs, y_cal)

    calibrated_model = CalibratedModelWrapper(best_raw_model, iso_calibrator)
    cal_test_probs = calibrated_model.predict_proba(X_test)[:, 1]

    raw_test_pr = average_precision_score(y_test, raw_test_probs)
    cal_test_pr = average_precision_score(y_test, cal_test_probs)
    cal_test_roc = roc_auc_score(y_test, cal_test_probs)
    cal_test_brier = brier_score_loss(y_test, cal_test_probs)

    logger.info(
        f"Final Performance on Holdout Test Set ({domain_name.upper()}): \n"
        f"  Raw PR-AUC:        {raw_test_pr:.4f}\n"
        f"  Calibrated PR-AUC: {cal_test_pr:.4f}\n"
        f"  Test ROC-AUC:      {cal_test_roc:.4f}\n"
        f"  Test Brier Score:  {cal_test_brier:.4f}"
    )

    # Save champion pipeline
    full_pipeline = Pipeline([
        ("preprocessor", preprocessor),
        ("model", calibrated_model),
    ])

    model_filename = f"{domain_name}_champion_pipeline.pkl"
    model_path = ARTIFACTS_DIR / model_filename
    with open(model_path, "wb") as f:
        pickle.dump(full_pipeline, f)
    logger.info(f"Saved {domain_name} champion pipeline to {model_path}")

    # Export Golden Rows (250 rows) for Java Parity
    golden_df = test_df.iloc[:250][feature_cols].copy()
    golden_rows = golden_df.to_dict(orient="records")
    golden_path = ARTIFACTS_DIR / f"golden_rows_{domain_name}.json"
    with open(golden_path, "w") as f:
        json.dump(golden_rows, f, indent=2)
    logger.info(f"Exported {len(golden_rows)} golden parity rows to {golden_path}")

    # Summary report
    summary = {
        "domain": domain_name,
        "champion_model": best_model_name,
        "feature_count": len(feature_cols),
        "features": feature_cols,
        "calibrated_pr_auc": float(cal_test_pr),
        "raw_pr_auc": float(raw_test_pr),
        "test_roc_auc": float(cal_test_roc),
        "test_brier_calibrated": float(cal_test_brier),
        "candidates": results,
    }
    summary_path = ARTIFACTS_DIR / f"{domain_name}_benchmark_summary.json"
    with open(summary_path, "w") as f:
        json.dump(summary, f, indent=2)

    return summary


def update_feature_spec():
    spec = {
        "purchase": {
            "version": "purchase-v2",
            "feature_count": len(PURCHASE_COLUMNS_29),
            "features": [
                {"name": "amount", "type": "float", "cold_start": 0.0},
                {"name": "log_amount", "type": "float", "cold_start": 0.0},
                {"name": "hour_of_day", "type": "int", "cold_start": 0},
                {"name": "day_of_week", "type": "int", "cold_start": 0},
                {"name": "is_weekend", "type": "int", "cold_start": 0},
                {"name": "is_night", "type": "int", "cold_start": 0},
                {"name": "customer_age", "type": "float", "cold_start": 0.0},
                {"name": "distance_home_merchant_km", "type": "float", "cold_start": 0.0},
                {"name": "city_pop_log", "type": "float", "cold_start": 0.0},
                {"name": "merchant_category", "type": "string", "cold_start": "unknown"},
                {"name": "customer_avg_amount", "type": "float", "cold_start": 0.0},
                {"name": "customer_std_amount", "type": "float", "cold_start": 0.0},
                {"name": "customer_max_amount", "type": "float", "cold_start": 0.0},
                {"name": "amount_to_avg_ratio", "type": "float", "cold_start": 0.0},
                {"name": "amount_zscore_customer", "type": "float", "cold_start": 0.0},
                {"name": "hour_share", "type": "float", "cold_start": 0.0},
                {"name": "category_share", "type": "float", "cold_start": 0.0},
                {"name": "merchant_visit_count", "type": "int", "cold_start": 0},
                {"name": "avg_distance_home_km", "type": "float", "cold_start": 0.0},
                {"name": "distance_zscore", "type": "float", "cold_start": 0.0},
                {"name": "txn_count_1h", "type": "int", "cold_start": 0},
                {"name": "txn_count_24h", "type": "int", "cold_start": 0},
                {"name": "txn_count_7d", "type": "int", "cold_start": 0},
                {"name": "amount_sum_24h", "type": "float", "cold_start": 0.0},
                {"name": "time_since_last_txn_s", "type": "float", "cold_start": 2592000.0},
                {"name": "is_new_merchant", "type": "int", "cold_start": 1},
                {"name": "is_new_category", "type": "int", "cold_start": 1},
                {"name": "history_length", "type": "int", "cold_start": 0},
                {"name": "days_since_first_txn", "type": "float", "cold_start": 0.0},
            ]
        },
        "transfer": {
            "version": "transfer-v2",
            "feature_count": len(TRANSFER_COLUMNS_20),
            "features": [
                {"name": "amount", "type": "float", "cold_start": 0.0},
                {"name": "log_amount", "type": "float", "cold_start": 0.0},
                {"name": "hour_of_day", "type": "int", "cold_start": 0},
                {"name": "day_of_week", "type": "int", "cold_start": 0},
                {"name": "sender_avg_amount", "type": "float", "cold_start": 0.0},
                {"name": "sender_std_amount", "type": "float", "cold_start": 0.0},
                {"name": "sender_max_amount", "type": "float", "cold_start": 0.0},
                {"name": "amount_to_avg_ratio", "type": "float", "cold_start": 0.0},
                {"name": "sender_amount_zscore", "type": "float", "cold_start": 0.0},
                {"name": "is_new_recipient", "type": "int", "cold_start": 1},
                {"name": "pair_transfer_count", "type": "int", "cold_start": 0},
                {"name": "recipient_share", "type": "float", "cold_start": 0.0},
                {"name": "days_since_last_pair_transfer", "type": "float", "cold_start": 30.0},
                {"name": "distinct_recipients_30d", "type": "int", "cold_start": 0},
                {"name": "recipient_inbound_count_24h", "type": "int", "cold_start": 0},
                {"name": "recipient_unique_senders_24h", "type": "int", "cold_start": 0},
                {"name": "sender_txn_count_1h", "type": "int", "cold_start": 0},
                {"name": "sender_txn_count_24h", "type": "int", "cold_start": 0},
                {"name": "amount_to_balance_ratio", "type": "float", "cold_start": 0.0},
                {"name": "history_length", "type": "int", "cold_start": 0},
            ]
        }
    }
    spec_path = ARTIFACTS_DIR / "feature_spec.json"
    with open(spec_path, "w") as f:
        json.dump(spec, f, indent=2)
    logger.info(f"Updated feature_spec.json (29 purchase, 20 transfer) at {spec_path}")


def main():
    logger.info("Starting Strictly Decoupled (Isolated) Dual-Domain Model Training...")
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

    update_feature_spec()

    p_summary = train_and_export_isolated_domain(
        domain_name="purchase",
        data_path=PURCHASE_DATA,
        feature_cols=PURCHASE_COLUMNS_29,
        cat_cols=["merchant_category"],
    )

    t_summary = train_and_export_isolated_domain(
        domain_name="transfer",
        data_path=TRANSFER_DATA,
        feature_cols=TRANSFER_COLUMNS_20,
        cat_cols=[],
    )

    logger.info("==================================================================")
    logger.info("STRICTLY DECOUPLED DUAL DOMAIN TRAINING COMPLETE!")
    logger.info(f"PURCHASE (29 Features): Champion={p_summary['champion_model'].upper()} | Calibrated PR-AUC: {p_summary['calibrated_pr_auc']:.4f} | ROC-AUC: {p_summary['test_roc_auc']:.4f}")
    logger.info(f"TRANSFER (20 Features): Champion={t_summary['champion_model'].upper()} | Calibrated PR-AUC: {t_summary['calibrated_pr_auc']:.4f} | ROC-AUC: {t_summary['test_roc_auc']:.4f}")
    logger.info("==================================================================")


if __name__ == "__main__":
    main()

