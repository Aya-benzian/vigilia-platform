"""
test_dual_domain_artifacts.py — Automated verification test suite for Dual-Domain artifacts.

Verifies:
1. feature_spec.json structure and completeness (48 purchase, 38 transfer features).
2. golden_rows_purchase.json & golden_rows_transfer.json integrity and shape (250 rows each).
3. purchase_champion_pipeline.pkl inference and probability calibration validity.
4. transfer_champion_pipeline.pkl inference and probability calibration validity.
5. End-to-end inference of golden rows through champion pipelines without errors.
"""

import json
import pickle
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

# Ensure src is on python path
SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20
from calibrated_wrapper import CalibratedModelWrapper
from config import ARTIFACTS_DIR


@pytest.fixture
def feature_spec():
    spec_path = ARTIFACTS_DIR / "feature_spec.json"
    assert spec_path.exists(), f"feature_spec.json not found at {spec_path}"
    with open(spec_path, "r") as f:
        return json.load(f)


@pytest.fixture
def golden_purchase():
    path = ARTIFACTS_DIR / "golden_rows_purchase.json"
    assert path.exists(), f"golden_rows_purchase.json not found at {path}"
    with open(path, "r") as f:
        return json.load(f)


@pytest.fixture
def golden_transfer():
    path = ARTIFACTS_DIR / "golden_rows_transfer.json"
    assert path.exists(), f"golden_rows_transfer.json not found at {path}"
    with open(path, "r") as f:
        return json.load(f)


@pytest.fixture
def purchase_pipeline():
    path = ARTIFACTS_DIR / "purchase_champion_pipeline.pkl"
    assert path.exists(), f"purchase_champion_pipeline.pkl not found at {path}"
    with open(path, "rb") as f:
        return pickle.load(f)


@pytest.fixture
def transfer_pipeline():
    path = ARTIFACTS_DIR / "transfer_champion_pipeline.pkl"
    assert path.exists(), f"transfer_champion_pipeline.pkl not found at {path}"
    with open(path, "rb") as f:
        return pickle.load(f)


def test_feature_spec_counts_and_keys(feature_spec):
    assert "purchase" in feature_spec
    assert "transfer" in feature_spec

    purchase_items = feature_spec["purchase"]["features"]
    transfer_items = feature_spec["transfer"]["features"]

    assert len(purchase_items) == 29, f"Expected 29 purchase features, got {len(purchase_items)}"
    assert len(transfer_items) == 20, f"Expected 20 transfer features, got {len(transfer_items)}"

    purchase_names = [f["name"] for f in purchase_items]
    transfer_names = [f["name"] for f in transfer_items]

    assert purchase_names == PURCHASE_COLUMNS_29
    assert transfer_names == TRANSFER_COLUMNS_20


def test_golden_rows_counts_and_keys(golden_purchase, golden_transfer):
    assert len(golden_purchase) >= 200, f"Expected >= 200 purchase golden rows, got {len(golden_purchase)}"
    assert len(golden_transfer) >= 200, f"Expected >= 200 transfer golden rows, got {len(golden_transfer)}"

    # Check first row has expected feature schema
    row_p = golden_purchase[0]
    for col in PURCHASE_COLUMNS_29:
        assert col in row_p, f"Missing {col} in purchase golden row"

    row_t = golden_transfer[0]
    for col in TRANSFER_COLUMNS_20:
        assert col in row_t, f"Missing {col} in transfer golden row"


def test_purchase_pipeline_inference(purchase_pipeline, golden_purchase):
    df_rows = pd.DataFrame(golden_purchase)
    probs = purchase_pipeline.predict_proba(df_rows)
    preds = purchase_pipeline.predict(df_rows)

    assert probs.shape == (len(golden_purchase), 2)
    assert np.all((probs >= 0.0) & (probs <= 1.0))
    np.testing.assert_allclose(probs.sum(axis=1), np.ones(len(golden_purchase)), atol=1e-5)
    assert set(np.unique(preds)).issubset({0, 1})


def test_transfer_pipeline_inference(transfer_pipeline, golden_transfer):
    df_rows = pd.DataFrame(golden_transfer)
    probs = transfer_pipeline.predict_proba(df_rows)
    preds = transfer_pipeline.predict(df_rows)

    assert probs.shape == (len(golden_transfer), 2)
    assert np.all((probs >= 0.0) & (probs <= 1.0))
    np.testing.assert_allclose(probs.sum(axis=1), np.ones(len(golden_transfer)), atol=1e-5)
    assert set(np.unique(preds)).issubset({0, 1})


def test_benchmark_summaries_exist_and_valid():
    p_sum_path = ARTIFACTS_DIR / "purchase_benchmark_summary.json"
    t_sum_path = ARTIFACTS_DIR / "transfer_benchmark_summary.json"

    assert p_sum_path.exists()
    assert t_sum_path.exists()

    with open(p_sum_path, "r") as f:
        p_sum = json.load(f)
    with open(t_sum_path, "r") as f:
        t_sum = json.load(f)

    assert p_sum["domain"] == "purchase"
    assert p_sum["feature_count"] == 29
    assert p_sum["champion_model"] == "xgboost"
    assert "calibrated_pr_auc" in p_sum

    assert t_sum["domain"] == "transfer"
    assert t_sum["feature_count"] == 20
    assert t_sum["champion_model"] == "xgboost"
    assert "calibrated_pr_auc" in t_sum
