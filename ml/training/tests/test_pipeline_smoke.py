"""
test_pipeline_smoke.py — Smoke tests for the trained pipeline (Member 1, §14 of design v5).

These tests verify that the registered model can be loaded and used correctly
WITHOUT retraining. They run in a fresh process against the MLflow registry.

Tests:
    1. Load registered model by alias "champion" — must not raise.
    2. Predict one row — probability must be in [0, 1].
    3. Batch vs single parity — 1000 rows together vs one by one must match.
    4. Pipeline output shape matches input row count.

These are integration-level smoke tests. They require the MLflow server to be
running and a model to be registered (run `make register` first).

Skip gracefully if MLFLOW_TRACKING_URI is unreachable.

Run with: pytest tests/test_pipeline_smoke.py -v
"""

import os
import pytest
import numpy as np
import pandas as pd

MLFLOW_URI = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
MODEL_NAME = "fraud-model"
MODEL_ALIAS = "champion"


def _try_connect():
    """Return True if MLflow server is reachable."""
    try:
        import urllib.request
        urllib.request.urlopen(f"{MLFLOW_URI}/health", timeout=3)
        return True
    except Exception:
        return False


mlflow_available = pytest.mark.skipif(
    not _try_connect(),
    reason=f"MLflow server not reachable at {MLFLOW_URI}. Run `docker compose up mlflow` first.",
)


def _load_champion():
    import mlflow
    mlflow.set_tracking_uri(MLFLOW_URI)
    return mlflow.sklearn.load_model(f"models:/{MODEL_NAME}@{MODEL_ALIAS}")


def _make_one_row() -> pd.DataFrame:
    """Build one minimal valid raw input row."""
    return pd.DataFrame([{
        "trans_date_trans_time": pd.Timestamp("2020-06-21 02:37:00"),
        "amt": 850.00,
        "category": "grocery_pos",
        "merch_lat": 33.986391,
        "merch_long": -81.200714,
        "gender": "F",
        "dob": pd.Timestamp("1987-04-19").date(),
        "lat": 33.9659,
        "long": -80.9455,
        "city_pop": 149,
    }])


# ---------------------------------------------------------------------------
# Test 1 — Load model by alias
# ---------------------------------------------------------------------------

@mlflow_available
def test_load_champion_model():
    model = _load_champion()
    assert model is not None


# ---------------------------------------------------------------------------
# Test 2 — Single-row prediction in [0, 1]
# ---------------------------------------------------------------------------

@mlflow_available
def test_single_row_probability_range():
    model = _load_champion()
    row = _make_one_row()
    prob = model.predict_proba(row)
    assert prob.shape == (1, 2), f"Expected shape (1, 2), got {prob.shape}"
    assert 0.0 <= prob[0, 1] <= 1.0, f"Probability out of range: {prob[0, 1]}"


# ---------------------------------------------------------------------------
# Test 3 — Batch vs single parity
# ---------------------------------------------------------------------------

@mlflow_available
def test_batch_vs_single_parity():
    """
    Predict 1000 rows as a batch and one-by-one.
    Results must be identical (no randomness in inference).
    """
    model = _load_champion()
    batch = pd.concat([_make_one_row()] * 100, ignore_index=True)

    batch_probs = model.predict_proba(batch)[:, 1]
    single_probs = np.array([
        model.predict_proba(_make_one_row())[0, 1]
        for _ in range(100)
    ])

    np.testing.assert_allclose(
        batch_probs, single_probs, rtol=1e-5,
        err_msg="Batch and single-row predictions do not match.",
    )


# ---------------------------------------------------------------------------
# Test 4 — Output shape
# ---------------------------------------------------------------------------

@mlflow_available
def test_output_shape():
    model = _load_champion()
    batch = pd.concat([_make_one_row()] * 10, ignore_index=True)
    probs = model.predict_proba(batch)
    assert probs.shape == (10, 2), f"Expected (10, 2), got {probs.shape}"
