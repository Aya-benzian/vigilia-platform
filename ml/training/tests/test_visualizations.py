"""
test_visualizations.py — Unit tests for Data Visualization artifacts & generation.
"""

import json

from config import ARTIFACTS_DIR
from generate_visualizations import (
    classify_error,
    compute_prediction_set,
    load_conformal_q_hat,
)


def test_classify_error():
    assert classify_error(true_label=1, predicted_label=1) == "TP"
    assert classify_error(true_label=0, predicted_label=0) == "TN"
    assert classify_error(true_label=0, predicted_label=1) == "FP"
    assert classify_error(true_label=1, predicted_label=0) == "FN"


def test_compute_prediction_set():
    # q_hat = 0.05
    # If prob_fraud = 0.01 -> prob_legit = 0.99
    # in_legit = (1 - 0.99) <= 0.05 -> True
    # in_fraud = (1 - 0.01) <= 0.05 -> False
    pred_set, is_ambig, status = compute_prediction_set(0.01, q_hat=0.05)
    assert pred_set == ["legitimate"]
    assert not is_ambig
    assert status == "SINGLETON"

    # If prob_fraud = 0.99 -> prob_legit = 0.01
    pred_set, is_ambig, status = compute_prediction_set(0.99, q_hat=0.05)
    assert pred_set == ["fraud"]
    assert not is_ambig
    assert status == "SINGLETON"

    # If prob_fraud = 0.50 and q_hat = 0.60
    # in_legit = 0.50 <= 0.60 -> True
    # in_fraud = 0.50 <= 0.60 -> True
    pred_set, is_ambig, status = compute_prediction_set(0.50, q_hat=0.60)
    assert pred_set == ["legitimate", "fraud"]
    assert is_ambig
    assert status == "AMBIGUOUS"


def test_load_conformal_q_hat():
    q_p = load_conformal_q_hat("purchase")
    q_t = load_conformal_q_hat("transfer")
    assert isinstance(q_p, float)
    assert isinstance(q_t, float)
    assert q_p >= 0.0
    assert q_t >= 0.0


def test_visualization_artifacts_exist_and_conform():
    required_metadata = [
        "schema_version",
        "family",
        "method",
        "dataset_name",
        "dataset_split",
        "sampling_strategy",
        "model_name",
        "model_version",
        "feature_spec_version",
        "preprocessing_version",
        "evaluation_threshold",
        "threshold_source",
        "generated_at",
        "points_count",
        "points",
    ]

    required_point_keys = [
        "transaction_id",
        "x",
        "y",
        "fraud_probability",
        "true_label",
        "predicted_label",
        "error_type",
        "prediction_set",
        "is_ambiguous",
        "conformal_status",
        "cluster_id",
        "is_outlier",
        "anomaly_score",
    ]

    for domain in ["purchase", "transfer"]:
        for method in ["pca", "tsne"]:
            art_name = f"{domain}_{method}_points.json"
            art_path = ARTIFACTS_DIR / art_name
            assert art_path.exists(), f"Missing artifact: {art_name}"

            with open(art_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)

            for key in required_metadata:
                assert key in data, f"Missing key '{key}' in {art_name}"

            assert data["family"] == domain
            assert data["method"] == method
            assert data["dataset_split"] == "test"
            assert data["feature_spec_version"] == f"{domain}-v2"
            assert data["points_count"] == len(data["points"])
            assert data["points_count"] > 0

            if method == "pca":
                assert "explained_variance_ratio" in data
                assert "cumulative_variance_explained" in data
                assert data["fitted_split"] == "train"
                assert len(data["explained_variance_ratio"]) == 2
            elif method == "tsne":
                assert "perplexity" in data
                assert "learning_rate" in data
                assert "max_iter" in data
                assert "random_state" in data

            # Verify point schema on first 10 points
            for pt in data["points"][:10]:
                for p_key in required_point_keys:
                    assert p_key in pt, f"Missing point key '{p_key}' in {art_name}"
                assert pt["true_label"] in ("legitimate", "fraud")
                assert pt["predicted_label"] in ("legitimate", "fraud")
                assert pt["error_type"] in ("TP", "TN", "FP", "FN")
                assert all(lbl in ("legitimate", "fraud") for lbl in pt["prediction_set"])
                assert 0.0 <= pt["fraud_probability"] <= 1.0


def test_pca_tsne_points_alignment():
    for domain in ["purchase", "transfer"]:
        pca_path = ARTIFACTS_DIR / f"{domain}_pca_points.json"
        tsne_path = ARTIFACTS_DIR / f"{domain}_tsne_points.json"

        with open(pca_path, "r", encoding="utf-8") as f:
            pca_pts = json.load(f)["points"]
        with open(tsne_path, "r", encoding="utf-8") as f:
            tsne_pts = json.load(f)["points"]

        assert len(pca_pts) == len(tsne_pts)

        for p, t in zip(pca_pts, tsne_pts):
            assert p["transaction_id"] == t["transaction_id"]
            assert p["true_label"] == t["true_label"]
            assert p["predicted_label"] == t["predicted_label"]
            assert p["error_type"] == t["error_type"]
            assert p["cluster_id"] == t["cluster_id"]
            assert p["anomaly_score"] == t["anomaly_score"]
            assert p["fraud_probability"] == t["fraud_probability"]


def test_pca_serialized_models_exist():
    p_pca = ARTIFACTS_DIR / "purchase_pca_model.pkl"
    t_pca = ARTIFACTS_DIR / "transfer_pca_model.pkl"
    assert p_pca.exists()
    assert t_pca.exists()

