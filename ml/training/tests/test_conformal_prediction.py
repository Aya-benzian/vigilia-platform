"""
test_conformal_prediction.py — Unit tests for Split Conformal Prediction calibration & evaluation.
"""

import json

import numpy as np
import pytest
from config import ARTIFACTS_DIR
from conformal_prediction import (
    DEFAULT_ALPHA,
    compute_conformal_quantile,
    compute_nonconformity_scores,
    evaluate_conformal_sets,
    predict_conformal_sets,
)


def test_compute_nonconformity_scores_values():
    # 3 samples:
    # 0: true=0, p=[0.9, 0.1] -> s = 1 - 0.9 = 0.1
    # 1: true=1, p=[0.2, 0.8] -> s = 1 - 0.8 = 0.2
    # 2: true=0, p=[0.4, 0.6] -> s = 1 - 0.4 = 0.6
    probs = np.array([
        [0.9, 0.1],
        [0.2, 0.8],
        [0.4, 0.6],
    ])
    y_true = np.array([0, 1, 0])
    scores = compute_nonconformity_scores(probs, y_true)

    assert len(scores) == 3
    assert np.allclose(scores, [0.1, 0.2, 0.6])


def test_compute_nonconformity_scores_validation():
    with pytest.raises(ValueError, match="Expected 2D probability array"):
        compute_nonconformity_scores(np.array([0.5, 0.5]), np.array([0]))

    with pytest.raises(ValueError, match="Mismatch between probabilities"):
        compute_nonconformity_scores(np.array([[0.8, 0.2]]), np.array([0, 1]))


def test_compute_conformal_quantile_finite():
    # n = 9, alpha = 0.10 -> (n+1)*(1-0.10) = 10 * 0.90 = 9 -> k = 9 <= 9
    scores = np.array([0.1, 0.3, 0.2, 0.9, 0.5, 0.4, 0.7, 0.6, 0.8])
    q_hat, k, status = compute_conformal_quantile(scores, alpha=0.10)

    assert k == 9
    assert status == "EXACT_FINITE"
    assert q_hat == 0.9  # sorted index 8


def test_compute_conformal_quantile_conservative_infinite():
    # n = 10, alpha = 0.05 -> (10+1)*0.95 = 10.45 -> k = 11 > 10
    scores = np.linspace(0.1, 0.9, 10)
    q_hat, k, status = compute_conformal_quantile(scores, alpha=0.05)

    assert k == 11
    assert status == "CONSERVATIVE_INFINITE"
    assert q_hat is None


def test_predict_conformal_sets():
    # q_hat = 0.25:
    # class 0 included if 1 - p0 <= 0.25 (i.e. p0 >= 0.75)
    # class 1 included if 1 - p1 <= 0.25 (i.e. p1 >= 0.75)
    probs = np.array([
        [0.90, 0.10],  # legit singleton
        [0.10, 0.90],  # fraud singleton
        [0.50, 0.50],  # empty (neither >= 0.75)
    ])
    sets = predict_conformal_sets(probs, q_hat=0.25)

    assert sets[0] == ["LEGITIMATE"]
    assert sets[1] == ["FRAUD"]
    assert sets[2] == []

    # If q_hat is large (e.g. 0.6), both classes are included for [0.50, 0.50]
    ambig_sets = predict_conformal_sets(np.array([[0.50, 0.50]]), q_hat=0.60)
    assert ambig_sets[0] == ["LEGITIMATE", "FRAUD"]

    # Conservative infinite
    inf_sets = predict_conformal_sets(probs, q_hat=None)
    assert all(s == ["LEGITIMATE", "FRAUD"] for s in inf_sets)


def test_evaluate_conformal_sets():
    pred_sets = [
        ["LEGITIMATE"],
        ["FRAUD"],
        ["LEGITIMATE", "FRAUD"],
        [],
    ]
    y_true = np.array([0, 1, 1, 0])

    metrics = evaluate_conformal_sets(pred_sets, y_true)

    # Covered:
    # 0: true=0, pred=['LEGITIMATE'] -> covered
    # 1: true=1, pred=['FRAUD'] -> covered
    # 2: true=1, pred=['LEGITIMATE', 'FRAUD'] -> covered
    # 3: true=0, pred=[] -> NOT covered
    # Marginal coverage = 3 / 4 = 0.75
    assert metrics["empirical_test_coverage"] == 0.75
    assert metrics["singleton_rate"] == 0.5
    assert metrics["ambiguous_rate"] == 0.25
    assert metrics["empty_rate"] == 0.25
    assert metrics["mean_set_size"] == 1.0


def test_conformal_artifacts_exist_and_meet_contract():
    required_keys = [
        "model_name",
        "model_version",
        "feature_spec_version",
        "method",
        "alpha",
        "q_hat",
        "quantile_status",
        "calibration_samples_count",
        "rank_k",
        "calibration_dataset_id",
        "test_samples_count",
        "empirical_test_coverage",
        "created_at",
        "metrics",
    ]

    for domain in ["purchase", "transfer"]:
        art_path = ARTIFACTS_DIR / f"{domain}_conformal_config.json"
        assert art_path.exists(), f"Missing artifact: {art_path}"

        with open(art_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)

        for k in required_keys:
            assert k in data, f"Key '{k}' missing from {art_path.name}"

        assert data["method"] == "split_conformal"
        assert data["alpha"] == DEFAULT_ALPHA
        assert data["quantile_status"] == "EXACT_FINITE"
        assert data["calibration_samples_count"] == 22500
        assert data["test_samples_count"] == 22500
        assert data["rank_k"] == 21376
        # Empirical test coverage must be >= 1 - alpha (>= 95%)
        assert data["empirical_test_coverage"] >= 0.95
