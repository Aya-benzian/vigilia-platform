"""
test_pipeline_smoke.py — Smoke tests for the registered MLflow pipelines (Member 1).

These tests verify that the registered champion models can be downloaded, loaded,
and invoked for predictions WITHOUT retraining. They run against the live MLflow server.

Domain Isolation (Supervisor Directive):
    - purchase-fraud-model : 29 pure purchase features (Sparkov)
    - transfer-fraud-model : 20 pure transfer features (PaySim)

Tests:
    1. Load purchase champion model by alias @champion.
    2. Predict probability on one purchase row — output in [0, 1].
    3. Batch vs single parity for purchase predictions.
    4. Pipeline output shape matches row count for purchase.
    5. Load transfer champion model by alias @champion.
    6. Predict probability on one transfer row — output in [0, 1].

Skip gracefully if MLFLOW_TRACKING_URI is unreachable.
"""

import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20

MLFLOW_URI = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
PURCHASE_MODEL = "purchase-fraud-model"
TRANSFER_MODEL = "transfer-fraud-model"
CHAMPION_ALIAS = "champion"


def _try_connect() -> bool:
    """Return True if MLflow server is reachable."""
    try:
        import urllib.request
        urllib.request.urlopen(f"{MLFLOW_URI}/health", timeout=3)
        return True
    except Exception:
        return False


mlflow_available = pytest.mark.skipif(
    not _try_connect(),
    reason=f"MLflow server not reachable at {MLFLOW_URI}.",
)


def _load_model(model_name: str):
    import mlflow
    import mlflow.sklearn
    mlflow.set_tracking_uri(MLFLOW_URI)
    return mlflow.sklearn.load_model(f"models:/{model_name}@{CHAMPION_ALIAS}")


def _make_purchase_row() -> pd.DataFrame:
    """Build one minimal valid purchase-domain row with exactly 29 features."""
    row = {col: 0.0 for col in PURCHASE_COLUMNS_29}
    row["amount"]                    = 45.00
    row["log_amount"]                = float(np.log1p(45.00))
    row["hour_of_day"]               = 14
    row["day_of_week"]               = 2
    row["is_weekend"]                = 0
    row["is_night"]                  = 0
    row["customer_age"]              = 35.0
    row["distance_home_merchant_km"] = 3.5
    row["city_pop_log"]              = float(np.log1p(50_000))
    row["merchant_category"]         = "grocery_pos"
    row["amount_to_avg_ratio"]       = 1.0
    row["amount_zscore_customer"]    = 0.0
    row["txn_count_1h"]              = 1
    row["txn_count_24h"]             = 3
    row["txn_count_7d"]              = 15
    row["history_length"]            = 30
    return pd.DataFrame([row])


def _make_transfer_row() -> pd.DataFrame:
    """Build one minimal valid transfer-domain row with exactly 20 features."""
    row = {col: 0.0 for col in TRANSFER_COLUMNS_20}
    row["amount"]                  = 500.00
    row["log_amount"]              = float(np.log1p(500.00))
    row["hour_of_day"]             = 10
    row["day_of_week"]             = 1
    row["sender_avg_amount"]       = 300.0
    row["amount_to_avg_ratio"]     = 1.67
    row["sender_amount_zscore"]    = 0.5
    row["is_new_recipient"]        = 0
    row["pair_transfer_count"]     = 5
    row["sender_txn_count_1h"]     = 1
    row["sender_txn_count_24h"]    = 2
    row["amount_to_balance_ratio"] = 0.1
    row["history_length"]          = 20
    return pd.DataFrame([row])


# ---------------------------------------------------------------------------
# Purchase Model Smoke Tests
# ---------------------------------------------------------------------------

@mlflow_available
def test_load_purchase_champion_model():
    """Test 1 — Load purchase model by alias."""
    model = _load_model(PURCHASE_MODEL)
    assert model is not None


@mlflow_available
def test_purchase_single_row_probability_range():
    """Test 2 — Single-row purchase prediction is probability in [0, 1]."""
    model = _load_model(PURCHASE_MODEL)
    row = _make_purchase_row()
    prob = model.predict_proba(row)
    assert prob.shape == (1, 2), f"Expected shape (1, 2), got {prob.shape}"
    assert 0.0 <= prob[0, 1] <= 1.0, f"Probability out of range: {prob[0, 1]}"


@mlflow_available
def test_purchase_batch_vs_single_parity():
    """Test 3 — Batch of 100 rows vs single rows gives identical predictions."""
    model = _load_model(PURCHASE_MODEL)
    batch = pd.concat([_make_purchase_row()] * 100, ignore_index=True)

    batch_probs = model.predict_proba(batch)[:, 1]
    single_probs = np.array([
        model.predict_proba(_make_purchase_row())[0, 1]
        for _ in range(100)
    ])

    np.testing.assert_allclose(
        batch_probs, single_probs, rtol=1e-5,
        err_msg="Batch and single-row predictions do not match.",
    )


@mlflow_available
def test_purchase_output_shape():
    """Test 4 — Output shape matches input row count."""
    model = _load_model(PURCHASE_MODEL)
    batch = pd.concat([_make_purchase_row()] * 10, ignore_index=True)
    probs = model.predict_proba(batch)
    assert probs.shape == (10, 2), f"Expected (10, 2), got {probs.shape}"


# ---------------------------------------------------------------------------
# Transfer Model Smoke Tests
# ---------------------------------------------------------------------------

@mlflow_available
def test_load_transfer_champion_model():
    """Test 5 — Load transfer model by alias."""
    model = _load_model(TRANSFER_MODEL)
    assert model is not None


@mlflow_available
def test_transfer_single_row_probability_range():
    """Test 6 — Single-row transfer prediction is probability in [0, 1]."""
    model = _load_model(TRANSFER_MODEL)
    row = _make_transfer_row()
    prob = model.predict_proba(row)
    assert prob.shape == (1, 2), f"Expected shape (1, 2), got {prob.shape}"
    assert 0.0 <= prob[0, 1] <= 1.0, f"Probability out of range: {prob[0, 1]}"
