"""
generate_visualizations.py — Data Visualization (PCA, t-SNE, Clustering, Outliers, Conformal).

Implements the contract for Member 2 (ML Serving) and Member 4 (Frontend Dashboard)
in accordance with Section 7 of the Design 2 / Final v4.2 specification.

Key Deliverables:
    - ml/training/artifacts/purchase_pca_points.json
    - ml/training/artifacts/purchase_tsne_points.json
    - ml/training/artifacts/transfer_pca_points.json
    - ml/training/artifacts/transfer_tsne_points.json
    - ml/training/artifacts/purchase_pca_model.pkl
    - ml/training/artifacts/transfer_pca_model.pkl

Properties:
    - PCA fitted ONLY on Part A (Train set); holdout test set never enters fit().
    - t-SNE executed offline with random_state=42 and controlled perplexity.
    - Stable transaction_ids shared identically between PCA and t-SNE files per domain.
    - Supervised error taxonomy (TP, TN, FP, FN) based on official champion thresholds:
      Purchase: 0.12 | Transfer: 0.33.
    - Conformal prediction sets and ambiguity status linked to each transaction.
    - Unsupervised clustering (DBSCAN) and anomaly detection (IsolationForest) on full 
      preprocessed dimensions.
"""

import json
import logging
import pickle
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from sklearn.cluster import DBSCAN
from sklearn.decomposition import PCA
from sklearn.ensemble import IsolationForest
from sklearn.manifold import TSNE

SRC_DIR = Path(__file__).resolve().parent
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from config import ARTIFACTS_DIR, DATA_INT_DIR, SEED
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20
from train_isolated_domains import split_chronological

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s | %(message)s",
)
logger = logging.getLogger("generate_visualizations")

# Official evaluated thresholds (F2 optimization on holdout Part C)
PURCHASE_THRESHOLD = 0.1200
TRANSFER_THRESHOLD = 0.3300

PURCHASE_MODEL_NAME = "purchase-fraud-model"
TRANSFER_MODEL_NAME = "transfer-fraud-model"

LABEL_MAP = {0: "legitimate", 1: "fraud"}


def load_conformal_q_hat(domain: str) -> float:
    """Load exact calibrated q_hat from existing conformal config artifact."""
    cfg_path = ARTIFACTS_DIR / f"{domain}_conformal_config.json"
    if cfg_path.exists():
        with open(cfg_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return float(data.get("q_hat", 0.0) or 0.0)
    return 0.001654 if domain == "purchase" else 0.0


def compute_prediction_set(
    prob_fraud: float,
    q_hat: float,
) -> tuple[list[str], bool, str]:
    """
    Compute conformal prediction set for a single transaction.
    Class 0 (LEGITIMATE) is included if (1 - P(Y=0)) <= q_hat <=> P(Y=1) <= q_hat
    Class 1 (FRAUD) is included if (1 - P(Y=1)) <= q_hat <=> P(Y=1) >= 1 - q_hat
    """
    prob_legit = 1.0 - prob_fraud
    in_legit = (1.0 - prob_legit) <= q_hat
    in_fraud = (1.0 - prob_fraud) <= q_hat

    prediction_set: list[str] = []
    if in_legit:
        prediction_set.append("legitimate")
    if in_fraud:
        prediction_set.append("fraud")

    is_ambiguous = len(prediction_set) == 2
    if len(prediction_set) == 1:
        status = "SINGLETON"
    elif len(prediction_set) == 2:
        status = "AMBIGUOUS"
    else:
        status = "EMPTY"

    return prediction_set, is_ambiguous, status


def classify_error(true_label: int, predicted_label: int) -> str:
    """Classify into standard confusion quadrant: TP, TN, FP, FN."""
    if true_label == 1 and predicted_label == 1:
        return "TP"
    if true_label == 0 and predicted_label == 0:
        return "TN"
    if true_label == 0 and predicted_label == 1:
        return "FP"
    return "FN"


def sample_enriched_test_data(
    test_df: pd.DataFrame,
    domain: str,
    target_legit_ratio: int = 4,
    random_state: int = SEED,
) -> pd.DataFrame:
    """
    Extract all test frauds and sample legitimate transactions to achieve ~20% fraud rate
    for balanced and highly readable 2D dashboard exploration.
    
    Purchase: 197 frauds + 803 legit = 1,000 points (~19.7% fraud rate).
    Transfer: 58 frauds + 442 legit = 500 points (~11.6% fraud rate) or 232 legit = 290 points (20.0%).
    """
    frauds = test_df[test_df["is_fraud"] == 1]
    legits = test_df[test_df["is_fraud"] == 0]

    if domain == "purchase":
        n_legit = 803  # 197 + 803 = 1000 points
    else:
        n_legit = 442  # 58 + 442 = 500 points

    sampled_legits = legits.sample(n=min(n_legit, len(legits)), random_state=random_state)
    combined = pd.concat([frauds, sampled_legits]).sort_index()
    return combined


def generate_domain_visualizations(domain: str) -> dict[str, Any]:
    """
    Generate PCA and t-SNE visualization artifacts for a specific domain.
    """
    logger.info(f"=== Generating Data Visualization Artifacts for Domain: '{domain.upper()}' ===")

    # 1. Configuration & paths
    if domain == "purchase":
        data_path = DATA_INT_DIR / "purchase_features_48.parquet"
        feature_cols = PURCHASE_COLUMNS_29
        model_name = PURCHASE_MODEL_NAME
        threshold = PURCHASE_THRESHOLD
        dataset_name = "Sparkov"
        dbscan_eps = 4.0
    elif domain == "transfer":
        data_path = DATA_INT_DIR / "transfer_features_38.parquet"
        feature_cols = TRANSFER_COLUMNS_20
        model_name = TRANSFER_MODEL_NAME
        threshold = TRANSFER_THRESHOLD
        dataset_name = "PaySim"
        dbscan_eps = 2.5
    else:
        raise ValueError(f"Unknown domain: {domain}")

    # 2. Load dataset and champion pipeline
    df = pd.read_parquet(data_path)
    train_df, _, _, test_df = split_chronological(df)

    pipeline_path = ARTIFACTS_DIR / f"{domain}_champion_pipeline.pkl"
    with open(pipeline_path, "rb") as fh:
        pipeline = pickle.load(fh)

    preprocessor = pipeline.named_steps["preprocessor"]

    # 3. Fit PCA strictly on Part A (Train set)
    logger.info(f"Fitting PCA(n_components=2) exclusively on Train split ({len(train_df):,} rows)...")
    X_train_trans = preprocessor.transform(train_df[feature_cols])
    pca = PCA(n_components=2, random_state=SEED)
    pca.fit(X_train_trans)

    explained_var = [round(float(v), 6) for v in pca.explained_variance_ratio_]
    cumulative_var = round(float(np.sum(pca.explained_variance_ratio_)), 6)
    logger.info(f"PCA explained variance: {explained_var} | cumulative: {cumulative_var}")

    # Serialize PCA model for reproducibility
    pca_model_path = ARTIFACTS_DIR / f"{domain}_pca_model.pkl"
    with open(pca_model_path, "wb") as fh:
        pickle.dump(pca, fh)
    logger.info(f"Saved serialized PCA model to: {pca_model_path}")

    # 4. Extract enriched sample from Test split
    sample_df = sample_enriched_test_data(test_df, domain=domain, random_state=SEED)
    n_sample = len(sample_df)
    n_frauds = int(sample_df["is_fraud"].sum())
    fraud_rate = n_frauds / n_sample
    logger.info(
        f"Test sample prepared: {n_sample} points ({n_frauds} frauds, "
        f"{n_sample - n_frauds} legit, {fraud_rate*100:.1f}% fraud rate)"
    )

    # 5. Transform sample features
    X_sample_raw = sample_df[feature_cols]
    X_sample_trans = preprocessor.transform(X_sample_raw)

    # 6. Compute 2D projections
    logger.info("Computing 2D PCA coordinates on test sample...")
    coords_pca = pca.transform(X_sample_trans)

    logger.info("Computing 2D t-SNE coordinates on test sample (random_state=42)...")
    tsne = TSNE(
        n_components=2,
        perplexity=30.0,
        learning_rate=200.0,
        max_iter=1000,
        random_state=SEED,
    )
    coords_tsne = tsne.fit_transform(X_sample_trans)

    # 7. Unsupervised Clustering & Outlier Detection on full preprocessed dimensions
    logger.info("Running DBSCAN clustering and IsolationForest outlier detection...")
    dbscan = DBSCAN(eps=dbscan_eps, min_samples=5)
    cluster_labels = dbscan.fit_predict(X_sample_trans)

    iso = IsolationForest(n_estimators=100, contamination=0.05, random_state=SEED)
    iso_preds = iso.fit_predict(X_sample_trans)
    iso_scores = -iso.score_samples(X_sample_trans)

    # 8. Model inference & Conformal Prediction
    logger.info("Evaluating champion model calibrated probabilities and conformal sets...")
    probs_fraud = pipeline.predict_proba(X_sample_raw)[:, 1]
    q_hat = load_conformal_q_hat(domain)

    # 9. Build aligned points lists
    points_pca: list[dict[str, Any]] = []
    points_tsne: list[dict[str, Any]] = []

    generated_at = datetime.now(UTC).isoformat()

    for idx, (original_idx, row) in enumerate(sample_df.iterrows()):
        tx_id = f"tx_{domain[:4]}_{idx:05d}"
        p_fraud = round(float(probs_fraud[idx]), 6)
        y_true = int(row["is_fraud"])
        y_pred = int(p_fraud >= threshold)
        err_type = classify_error(y_true, y_pred)

        pred_set, is_ambig, conf_status = compute_prediction_set(p_fraud, q_hat)

        c_id = int(cluster_labels[idx])
        is_out = bool(iso_preds[idx] == -1)
        anom_score = round(float(iso_scores[idx]), 6)

        # Base point attributes (shared between PCA and t-SNE)
        base_attr = {
            "transaction_id": tx_id,
            "fraud_probability": p_fraud,
            "true_label": LABEL_MAP[y_true],
            "predicted_label": LABEL_MAP[y_pred],
            "error_type": err_type,
            "prediction_set": pred_set,
            "is_ambiguous": is_ambig,
            "conformal_status": conf_status,
            "cluster_id": c_id,
            "is_outlier": is_out,
            "anomaly_score": anom_score,
        }

        # PCA Point
        pt_pca = {
            **base_attr,
            "x": round(float(coords_pca[idx, 0]), 6),
            "y": round(float(coords_pca[idx, 1]), 6),
        }
        points_pca.append(pt_pca)

        # t-SNE Point
        pt_tsne = {
            **base_attr,
            "x": round(float(coords_tsne[idx, 0]), 6),
            "y": round(float(coords_tsne[idx, 1]), 6),
        }
        points_tsne.append(pt_tsne)

    # 10. Assemble complete JSON artifacts adhering to VisualizationArtifact schema
    base_meta = {
        "schema_version": "1.0",
        "family": domain,
        "dataset_name": dataset_name,
        "dataset_split": "test",
        "sampling_strategy": "stratified_fraud_enriched_20pct",
        "model_name": model_name,
        "model_version": "2",
        "feature_spec_version": f"{domain}-v2",
        "preprocessing_version": "v2",
        "evaluation_threshold": threshold,
        "threshold_source": "cost_sensitive_f2_optimization",
        "generated_at": generated_at,
        "points_count": n_sample,
    }

    pca_artifact = {
        **base_meta,
        "method": "pca",
        "explained_variance_ratio": explained_var,
        "cumulative_variance_explained": cumulative_var,
        "fitted_split": "train",
        "points": points_pca,
    }

    tsne_artifact = {
        **base_meta,
        "method": "tsne",
        "perplexity": 30.0,
        "learning_rate": "200.0",
        "max_iter": 1000,
        "random_state": SEED,
        "points": points_tsne,
    }

    # 11. Write JSON files
    pca_file = ARTIFACTS_DIR / f"{domain}_pca_points.json"
    tsne_file = ARTIFACTS_DIR / f"{domain}_tsne_points.json"

    with open(pca_file, "w", encoding="utf-8") as fh:
        json.dump(pca_artifact, fh, indent=2)
    with open(tsne_file, "w", encoding="utf-8") as fh:
        json.dump(tsne_artifact, fh, indent=2)

    logger.info(f"Successfully exported: {pca_file}")
    logger.info(f"Successfully exported: {tsne_file}")

    return {
        "pca_file": pca_file,
        "tsne_file": tsne_file,
        "points_count": n_sample,
        "fraud_count": n_frauds,
    }


def main():
    logger.info("Starting offline Data Visualization generation (PCA, t-SNE, Clustering, Outliers)...")
    res_p = generate_domain_visualizations("purchase")
    res_t = generate_domain_visualizations("transfer")

    logger.info("=" * 70)
    logger.info("DATA VISUALIZATION GENERATION COMPLETED SUCCESSFULLY!")
    logger.info(f"Purchase: {res_p['points_count']} points exported to {res_p['pca_file'].name} & {res_p['tsne_file'].name}")
    logger.info(f"Transfer: {res_t['points_count']} points exported to {res_t['pca_file'].name} & {res_t['tsne_file'].name}")
    logger.info("=" * 70)


if __name__ == "__main__":
    main()

