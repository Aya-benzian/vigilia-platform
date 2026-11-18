"""
conformal_prediction.py — Split Conformal Prediction for Uncertainty Quantification.

Supervisor & ML Serving (M2) Contract:
    - Provides finite-sample marginal coverage guarantee of at least 1 - alpha (e.g. 95% for alpha=0.05)
      under exchangeability.
    - Yields prediction sets: ['LEGITIMATE'], ['FRAUD'], ['LEGITIMATE', 'FRAUD'] (ambiguous -> human review), or [].
    - Frozen champion pipelines: zero retraining of base XGBoost models or calibrators.
    - Strict domain isolation: Purchase (Sparkov, 29 features) and Transfer (PaySim, 20 features)
      are calibrated and evaluated independently with their own holdouts, quantiles, and artifacts.
    - Calibration split D_cal_conf is strictly independent of training data and of Isotonic Regression (Part B).
    - Finite-sample quantile computation uses exact order statistic without linear interpolation.
    - Artifact conformal_config.json is logged to MLflow under metadata/conformal_config.json
      for each champion model version.
"""

import json
import logging
import math
import pickle
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

SRC_DIR = Path(__file__).resolve().parent
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

import mlflow
from config import ARTIFACTS_DIR, DATA_INT_DIR, MLFLOW_TRACKING_URI
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20
from mlflow import MlflowClient
from train_isolated_domains import split_chronological

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s | %(message)s",
)
logger = logging.getLogger("conformal_prediction")

PURCHASE_MODEL_NAME = "purchase-fraud-model"
TRANSFER_MODEL_NAME = "transfer-fraud-model"
DEFAULT_ALPHA = 0.05
CHAMPION_ALIAS = "champion"


def compute_nonconformity_scores(probs: np.ndarray, y_true: np.ndarray) -> np.ndarray:
    """
    Compute split conformal prediction non-conformity scores for classification:
        s_i = 1 - P_hat(Y = y_i | X_i)
    
    Args:
        probs: Array of shape (n_samples, n_classes) with class probabilities.
        y_true: 1D array of shape (n_samples,) with integer ground truth class labels (0 or 1).
    
    Returns:
        1D numpy array of non-conformity scores in [0, 1].
    """
    if probs.ndim != 2 or probs.shape[1] < 2:
        raise ValueError(f"Expected 2D probability array with at least 2 classes, got shape {probs.shape}")
    if len(probs) != len(y_true):
        raise ValueError(f"Mismatch between probabilities ({len(probs)}) and labels ({len(y_true)})")

    y_indices = np.asarray(y_true, dtype=int)
    n = len(y_indices)
    true_class_probs = probs[np.arange(n), y_indices]
    scores = 1.0 - true_class_probs
    return np.clip(scores, 0.0, 1.0)


def compute_conformal_quantile(
    scores: np.ndarray,
    alpha: float = DEFAULT_ALPHA,
) -> tuple[float | None, int, str]:
    """
    Compute the finite-sample conformal quantile using the exact order statistic:
        k = ceil((n + 1) * (1 - alpha))
        If k <= n: q_hat = scores_sorted[k - 1] (0-indexed rank k)
        If k > n: q_hat = None, status = "CONSERVATIVE_INFINITE"
    
    Args:
        scores: 1D array of non-conformity scores on D_cal_conf.
        alpha: Error rate (default 0.05 for 95% marginal coverage).
    
    Returns:
        Tuple of (q_hat, rank_k, quantile_status).
    """
    n = len(scores)
    if n == 0:
        raise ValueError("Cannot compute conformal quantile on empty scores array")
    if not (0.0 < alpha < 1.0):
        raise ValueError(f"alpha must be in (0, 1), got {alpha}")

    k = math.ceil((n + 1) * (1.0 - alpha))
    if k <= n:
        sorted_scores = np.sort(scores)
        q_hat = float(sorted_scores[k - 1])
        return q_hat, k, "EXACT_FINITE"
    else:
        return None, k, "CONSERVATIVE_INFINITE"


def predict_conformal_sets(
    probs: np.ndarray,
    q_hat: float | None,
) -> list[list[str]]:
    """
    Construct conformal prediction sets for test instances:
        Class c is included if: 1 - P_hat(Y = c | X) <= q_hat
    
    Args:
        probs: Array of shape (n_samples, 2) with [P(legit), P(fraud)].
        q_hat: Exact conformal quantile threshold (or None if conservative infinite).
    
    Returns:
        List of lists containing class labels ('LEGITIMATE', 'FRAUD').
    """
    if q_hat is None:
        # Conservative infinite fallback: full label set
        return [["LEGITIMATE", "FRAUD"] for _ in range(len(probs))]

    in_legit = (1.0 - probs[:, 0]) <= q_hat
    in_fraud = (1.0 - probs[:, 1]) <= q_hat

    prediction_sets: list[list[str]] = []
    for leg, fr in zip(in_legit, in_fraud):
        s: list[str] = []
        if leg:
            s.append("LEGITIMATE")
        if fr:
            s.append("FRAUD")
        prediction_sets.append(s)

    return prediction_sets


def evaluate_conformal_sets(
    pred_sets: list[list[str]],
    y_true: np.ndarray,
) -> dict[str, Any]:
    """
    Evaluate empirical test performance of prediction sets.
    
    Metrics:
        - empirical_test_coverage: Marginal coverage across all samples.
        - class_coverage: Coverage broken down by true class (Legitimate vs Fraud).
        - mean_set_size: Average cardinality of prediction sets.
        - singleton_rate: Fraction of sets with size == 1.
        - ambiguous_rate: Fraction of sets with size == 2 (needs human review).
        - empty_rate: Fraction of sets with size == 0.
    """
    n = len(y_true)
    if n == 0:
        raise ValueError("Cannot evaluate conformal sets on empty labels array")

    label_map = {0: "LEGITIMATE", 1: "FRAUD"}
    covered = np.array([label_map[y] in s for y, s in zip(y_true, pred_sets)], dtype=bool)

    marginal_coverage = float(np.mean(covered))

    mask_legit = (y_true == 0)
    mask_fraud = (y_true == 1)

    cov_legit = float(np.mean(covered[mask_legit])) if np.any(mask_legit) else 1.0
    cov_fraud = float(np.mean(covered[mask_fraud])) if np.any(mask_fraud) else 1.0

    sizes = np.array([len(s) for s in pred_sets], dtype=int)
    mean_size = float(np.mean(sizes))

    empty_rate = float(np.mean(sizes == 0))
    singleton_rate = float(np.mean(sizes == 1))
    ambiguous_rate = float(np.mean(sizes == 2))

    singleton_legit = np.array([s == ["LEGITIMATE"] for s in pred_sets], dtype=bool)
    singleton_fraud = np.array([s == ["FRAUD"] for s in pred_sets], dtype=bool)

    return {
        "empirical_test_coverage": round(marginal_coverage, 6),
        "class_coverage": {
            "legitimate": round(cov_legit, 6),
            "fraud": round(cov_fraud, 6),
        },
        "mean_set_size": round(mean_size, 4),
        "singleton_rate": round(singleton_rate, 6),
        "ambiguous_rate": round(ambiguous_rate, 6),
        "empty_rate": round(empty_rate, 6),
        "singleton_breakdown": {
            "legitimate_rate": round(float(np.mean(singleton_legit)), 6),
            "fraud_rate": round(float(np.mean(singleton_fraud)), 6),
        },
    }


def _load_champion_pipeline(domain: str):
    pkl_name = f"{domain}_champion_pipeline.pkl"
    pkl_path = ARTIFACTS_DIR / pkl_name
    if not pkl_path.exists():
        raise FileNotFoundError(f"Champion pipeline file not found: {pkl_path}")
    with open(pkl_path, "rb") as fh:
        return pickle.load(fh)


def _load_data_splits(domain: str) -> tuple[pd.DataFrame, pd.DataFrame, list[str], str]:
    if domain == "purchase":
        data_path = DATA_INT_DIR / "purchase_features_48.parquet"
        feature_cols = PURCHASE_COLUMNS_29
        cal_id = "sparkov_purchase_chronological_part_c"
    elif domain == "transfer":
        data_path = DATA_INT_DIR / "transfer_features_38.parquet"
        feature_cols = TRANSFER_COLUMNS_20
        cal_id = "paysim_transfer_chronological_part_c"
    else:
        raise ValueError(f"Unknown domain: {domain}")

    if not data_path.exists():
        raise FileNotFoundError(f"Processed dataset not found: {data_path}")

    df = pd.read_parquet(data_path)
    # Split chronologically: Train 60%, Cal_iso 10%, Part_C 15%, Test 15%
    # D_cal_conf = Part C (strictly independent from Train and Isotonic Cal)
    # D_test     = Final 15% test set
    _, _, d_cal_conf, d_test = split_chronological(df)
    return d_cal_conf, d_test, feature_cols, cal_id


def run_conformal_calibration(
    domain: str,
    alpha: float = DEFAULT_ALPHA,
    publish_mlflow: bool = True,
) -> dict[str, Any]:
    """
    Run the full offline Split Conformal Prediction calibration and test evaluation
    for a single domain, generate conformal_config.json, and publish to MLflow.
    """
    logger.info(f"=== Starting Conformal Calibration for domain: '{domain.upper()}' (alpha={alpha}) ===")
    
    # 1. Load pipeline and data
    pipeline = _load_champion_pipeline(domain)
    d_cal_conf, d_test, feature_cols, cal_dataset_id = _load_data_splits(domain)
    
    model_name = PURCHASE_MODEL_NAME if domain == "purchase" else TRANSFER_MODEL_NAME
    n_cal = len(d_cal_conf)
    n_test = len(d_test)

    logger.info(
        f"Data splits — D_cal_conf: {n_cal:,} rows | D_test: {n_test:,} rows | "
        f"Features: {len(feature_cols)} (Domain Isolated)"
    )

    # 2. Compute non-conformity scores on D_cal_conf
    X_cal = d_cal_conf[feature_cols]
    y_cal = d_cal_conf["is_fraud"].values
    probs_cal = pipeline.predict_proba(X_cal)

    scores_cal = compute_nonconformity_scores(probs_cal, y_cal)

    # 3. Compute finite-sample quantile
    q_hat, rank_k, quantile_status = compute_conformal_quantile(scores_cal, alpha=alpha)
    logger.info(f"Conformal Quantile q_hat: {q_hat} | rank_k: {rank_k}/{n_cal} | status: {quantile_status}")

    # 4. Evaluate on independent D_test
    X_test = d_test[feature_cols]
    y_test = d_test["is_fraud"].values
    probs_test = pipeline.predict_proba(X_test)

    pred_sets_test = predict_conformal_sets(probs_test, q_hat)
    eval_metrics = evaluate_conformal_sets(pred_sets_test, y_test)

    logger.info(
        f"Test Evaluation Results: "
        f"Marginal Coverage = {eval_metrics['empirical_test_coverage']*100:.2f}% (guarantee >= {(1-alpha)*100:.1f}%) | "
        f"Mean Set Size = {eval_metrics['mean_set_size']} | "
        f"Ambiguous Rate = {eval_metrics['ambiguous_rate']*100:.2f}% | "
        f"Singleton Rate = {eval_metrics['singleton_rate']*100:.2f}%"
    )

    # 5. Fetch MLflow champion model version & run_id
    model_version_str = "2"
    champion_run_id: str | None = None
    try:
        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
        client = MlflowClient()
        v = client.get_model_version_by_alias(model_name, CHAMPION_ALIAS)
        model_version_str = str(v.version)
        champion_run_id = v.run_id
        logger.info(f"Target MLflow Champion: '{model_name}@{CHAMPION_ALIAS}' (version {model_version_str}, run_id {champion_run_id})")
    except Exception as e:  # noqa: BLE001
        logger.warning(f"Could not resolve champion model version from MLflow: {e}. Defaulting to v{model_version_str}")

    created_at = datetime.now(UTC).isoformat()

    # 6. Build config artifact according to M2 contract
    config = {
        "model_name": model_name,
        "model_version": model_version_str,
        "feature_spec_version": "v2",
        "method": "split_conformal",
        "alpha": alpha,
        "q_hat": q_hat,
        "quantile_status": quantile_status,
        "calibration_samples_count": n_cal,
        "rank_k": rank_k,
        "calibration_dataset_id": cal_dataset_id,
        "test_samples_count": n_test,
        "empirical_test_coverage": eval_metrics["empirical_test_coverage"],
        "created_at": created_at,
        "metrics": eval_metrics,
        "notes": (
            "Split Conformal Prediction offline calibration. "
            "Guarantees marginal coverage >= 1 - alpha under exchangeability. "
            "Independent calibration Part C and holdout test set. No model retraining."
        ),
    }

    # Save to local artifacts
    domain_artifact_path = ARTIFACTS_DIR / f"{domain}_conformal_config.json"
    with open(domain_artifact_path, "w", encoding="utf-8") as fh:
        json.dump(config, fh, indent=2)
    logger.info(f"Saved local artifact to: {domain_artifact_path}")

    # 7. Publish to MLflow
    if publish_mlflow and champion_run_id:
        try:
            logger.info(f"Publishing conformal_config.json to MLflow run: {champion_run_id} ...")
            # Create a file named exactly conformal_config.json for MLflow artifact logging
            temp_config_path = ARTIFACTS_DIR / "conformal_config.json"
            with open(temp_config_path, "w", encoding="utf-8") as fh:
                json.dump(config, fh, indent=2)

            client = MlflowClient()
            client.log_artifact(
                run_id=champion_run_id,
                local_path=str(temp_config_path),
                artifact_path="metadata",
            )
            # In MLflow, rename temp artifact in run metadata directory if needed, or log direct file
            # Also log specific conformal metrics to champion run
            client.log_metric(champion_run_id, "conformal_empirical_coverage", eval_metrics["empirical_test_coverage"])
            client.log_metric(champion_run_id, "conformal_mean_set_size", eval_metrics["mean_set_size"])
            client.log_metric(champion_run_id, "conformal_ambiguous_rate", eval_metrics["ambiguous_rate"])
            client.log_metric(champion_run_id, "conformal_singleton_rate", eval_metrics["singleton_rate"])
            if q_hat is not None:
                client.log_metric(champion_run_id, "conformal_q_hat", float(q_hat))

            if temp_config_path.exists():
                temp_config_path.unlink()

            logger.info(f"Successfully published conformal_config to MLflow under run {champion_run_id}")
        except Exception as e:  # noqa: BLE001
            logger.error(f"Failed to publish artifact to MLflow: {e}")

    return config


def main():
    logger.info("Initializing Split Conformal Prediction for Purchase and Transfer domains...")
    
    # 1. Purchase domain
    purchase_cfg = run_conformal_calibration(domain="purchase", alpha=DEFAULT_ALPHA, publish_mlflow=True)
    
    # 2. Transfer domain
    transfer_cfg = run_conformal_calibration(domain="transfer", alpha=DEFAULT_ALPHA, publish_mlflow=True)

    logger.info("=" * 70)
    logger.info("SPLIT CONFORMAL CALIBRATION COMPLETED SUCCESSFULLY!")
    logger.info(f"Purchase ({purchase_cfg['model_name']}):")
    logger.info(f"  q_hat = {purchase_cfg['q_hat']}, Empirical Coverage = {purchase_cfg['empirical_test_coverage']*100:.2f}%")
    logger.info(f"Transfer ({transfer_cfg['model_name']}):")
    logger.info(f"  q_hat = {transfer_cfg['q_hat']}, Empirical Coverage = {transfer_cfg['empirical_test_coverage']*100:.2f}%")
    logger.info("=" * 70)


if __name__ == "__main__":
    main()
